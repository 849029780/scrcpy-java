package com.jian.scrcpy.media;

import com.jian.scrcpy.protocol.*;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public final class FfmpegVideoDecoder extends SimpleChannelInboundHandler<MediaMessage> implements AutoCloseable {
    private final Consumer<VideoFrame> sink;
    private AVCodecContext ctx;
    private AVFrame frame, rgb;
    private AVPacket packet;
    private SwsContext sws;
    private int width, height;
    private int pixelFormat = -1;

    // Diagnostic only: bypass swscale/BGRA/JavaFX while keeping H.264 decode active.
    private static final boolean DEBUG_SKIP_RENDER =
            Boolean.getBoolean("scrcpy.video.debug.skip-render");
    // Diagnostic only: bypass all FFmpeg video decode while keeping MediaMessage/ByteBuf flow active.
    private static final boolean DEBUG_SKIP_DECODE =
            Boolean.getBoolean("scrcpy.video.debug.skip-decode");
    // Diagnostic only: keep decoder/frame conversion setup, but bypass sws_scale().
    private static final boolean DEBUG_SKIP_SWS_SCALE =
            Boolean.getBoolean("scrcpy.video.debug.skip-sws-scale");

    // Diagnostic only: serialize native FFmpeg video operations across all
    // device decoders to test whether multi-device native concurrency is the cause.
    private static final Object FFMPEG_VIDEO_LOCK = new Object();
    private final BlockingQueue<MediaMessage> decodeQueue = new LinkedBlockingQueue<>(120);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread decodeThread;

    public FfmpegVideoDecoder(Consumer<VideoFrame> s) {
        super(false);
        sink = s;
        decodeThread = Thread.ofVirtual().name("scrcpy-video-decoder").start(this::decodeLoop);
    }

    private static int avId(CodecId c) {
        return switch (c) {
            case H264 -> AV_CODEC_ID_H264;
            case H265 -> AV_CODEC_ID_HEVC;
            case AV1 -> AV_CODEC_ID_AV1;
            default -> throw new IllegalArgumentException("Not video: " + c);
        };
    }

    private void init(CodecId c) {
        // A decoder instance should normally receive one Codec message.
        // Make repeated initialization safe and deterministic.
        closeNative();

        AVCodec dec = avcodec_find_decoder(avId(c));
        if (dec == null) throw new IllegalStateException("decoder unavailable " + c);
        ctx = avcodec_alloc_context3(dec);
        if (ctx == null || avcodec_open2(ctx, dec, (AVDictionary) null) < 0)
            throw new IllegalStateException("cannot open " + c);
        frame = av_frame_alloc();
        rgb = av_frame_alloc();
        packet = av_packet_alloc();
    }

    private void converter(int w, int h, int fmt) {
        if (w == width && h == height && fmt == pixelFormat && sws != null) {
            return;
        }

        if (sws != null) {
            sws_freeContext(sws);
            sws = null;
        }

        width = w;
        height = h;
        pixelFormat = fmt;

        sws = sws_getContext(
                w, h, fmt,
                w, h, AV_PIX_FMT_BGRA,
                SWS_BILINEAR,
                null, null, (DoublePointer) null);
        if (sws == null) {
            throw new IllegalStateException(
                    "cannot create swscale context: "
                            + w + "x" + h + " fmt=" + fmt);
        }

        // Let FFmpeg allocate and own the destination frame buffer.
        // This avoids mixing JavaCPP-owned raw memory with AVFrame data
        // while sws_scale() is writing to the destination.
        av_frame_unref(rgb);
        rgb.width(w);
        rgb.height(h);
        rgb.format(AV_PIX_FMT_BGRA);

        if (av_frame_get_buffer(rgb, 32) < 0) {
            throw new IllegalStateException(
                    "cannot allocate BGRA frame buffer: "
                            + w + "x" + h);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ch, MediaMessage m) {
        // Never run FFmpeg/sws_scale on a Netty I/O event loop. Retain packet
        // data and hand it to one ordered virtual-thread consumer.
        if (closed.get()) {
            releaseMessage(m);
            return;
        }
        if (!decodeQueue.offer(m)) {
            // Keep latency bounded under overload. Video packets are droppable;
            // discard the oldest queued packet rather than blocking Netty.
            MediaMessage dropped = decodeQueue.poll();
            releaseMessage(dropped);
            if (!decodeQueue.offer(m)) {
                releaseMessage(m);
            }
        }
    }

    private void decodeLoop() {
        try {
            while (!closed.get() || !decodeQueue.isEmpty()) {
                MediaMessage m = decodeQueue.take();
                try {
                    decodeMessage(m);
                } finally {
                    releaseMessage(m);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("[scrcpy-java] video decode worker failed: " + t);
            t.printStackTrace(System.err);
        } finally {
            MediaMessage m;
            while ((m = decodeQueue.poll()) != null) releaseMessage(m);
            // Native FFmpeg/sws state is released by the decode worker itself,
            // never concurrently with decodeMessage().
            closeNative();
        }
    }

    private void decodeMessage(MediaMessage m) {
        synchronized (FFMPEG_VIDEO_LOCK) {
            decodeMessage0(m);
        }
    }

    private void decodeMessage0(MediaMessage m) {
        if (m instanceof MediaMessage.Codec c) {
            if (!DEBUG_SKIP_DECODE) {
                init(c.codec());
            }
            return;
        }
        if (!(m instanceof MediaMessage.Packet p) || p.config()) return;
        if (DEBUG_SKIP_DECODE) {
            return;
        }
        byte[] b = new byte[p.data().readableBytes()];
        int payloadSize = b.length;
        p.data().getBytes(p.data().readerIndex(), b);

        // Let FFmpeg own the packet payload. The previous implementation
        // pointed AVPacket.data at a temporary JavaCPP BytePointer.
        av_packet_unref(packet);
        if (av_new_packet(packet, payloadSize) < 0) return;
        packet.data().put(b, 0, payloadSize);
        packet.pts(p.pts()).dts(p.pts());

        if (avcodec_send_packet(ctx, packet) < 0) return;
            while (avcodec_receive_frame(ctx, frame) >= 0) {
                if (DEBUG_SKIP_RENDER) {
                    continue;
                }
                converter(frame.width(), frame.height(), frame.format());
                if (DEBUG_SKIP_SWS_SCALE) {
                    continue;
                }
                System.out.printf(
                        "[scrcpy-video] sws_scale begin "
                                + "src=%dx%d fmt=%d dst=%dx%d "
                                + "srcStride=[%d,%d,%d,%d] dstStride=%d%n",
                        frame.width(), frame.height(), frame.format(),
                        width, height,
                        frame.linesize(0), frame.linesize(1),
                        frame.linesize(2), frame.linesize(3),
                        rgb.linesize(0));

                int scaled = sws_scale(
                        sws,
                        frame.data(),
                        frame.linesize(),
                        0,
                        frame.height(),
                        rgb.data(),
                        rgb.linesize());

                System.out.printf("[scrcpy-video] sws_scale end: %d%n", scaled);

                if (scaled <= 0) {
                    continue;
                }

                int stride = rgb.linesize(0), n = width * height * 4;
                byte[] out = new byte[n];
                BytePointer pixels = rgb.data(0);
                if (stride == width * 4) {
                    pixels.position(0).get(out);
                } else {
                    for (int y = 0; y < height; y++) {
                        pixels.position((long) y * stride).get(out, y * width * 4, width * 4);
                    }
                }
                sink.accept(new VideoFrame(width, height, out, p.pts()));
            }
    }

    private static void releaseMessage(MediaMessage m) {
        if (m instanceof MediaMessage.Packet p) {
            ReferenceCountUtil.release(p.data());
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Interrupt the worker so it stops waiting for more packets. Native
        // FFmpeg resources are released by the worker itself after it exits,
        // never concurrently with decodeMessage().
        decodeThread.interrupt();
        try {
            decodeThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void closeNative() {
        if (sws != null) {
            sws_freeContext(sws);
            sws = null;
        }
        if (rgbBuffer != null) {
            rgbBuffer.close();
            rgbBuffer = null;
        }
        if (packet != null) av_packet_free(packet);
        if (rgb != null) av_frame_free(rgb);
        if (frame != null) av_frame_free(frame);
        if (ctx != null) {
            avcodec_free_context(ctx);
            ctx = null;
        }
    }
}