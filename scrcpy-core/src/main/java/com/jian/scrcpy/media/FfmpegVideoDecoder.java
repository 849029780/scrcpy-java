package com.jian.scrcpy.media;

import com.jian.scrcpy.protocol.*;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.*;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public final class FfmpegVideoDecoder extends SimpleChannelInboundHandler<MediaMessage> implements AutoCloseable {
    private final Consumer<VideoFrame> sink;
    private final String decoderMode;
    private AVCodecContext ctx;
    private AVFrame frame, softwareFrame, rgb;
    private AVPacket packet;
    private SwsContext sws;
    private AVBufferRef hwDevice;
    private AVCodecContext.Get_format_AVCodecContext_IntPointer getFormatCallback;
    private int hwPixelFormat = AV_PIX_FMT_NONE;
    private String activeHardware;
    private int width, height;
    private int pixelFormat = -1;

    private static final boolean DEBUG_SKIP_RENDER =
            Boolean.getBoolean("scrcpy.video.debug.skip-render");
    private static final boolean DEBUG_SKIP_DECODE =
            Boolean.getBoolean("scrcpy.video.debug.skip-decode");
    private static final boolean DEBUG_SKIP_SWS_SCALE =
            Boolean.getBoolean("scrcpy.video.debug.skip-sws-scale");

    // Keep the existing global native lock for stability with multi-device
    // decoding. Hardware decoding can be made more parallel later once every
    // backend has been stress-tested independently.
    private static final Object FFMPEG_VIDEO_LOCK = new Object();
    private final BlockingQueue<MediaMessage> decodeQueue = new LinkedBlockingQueue<>(120);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread decodeThread;

    public FfmpegVideoDecoder(Consumer<VideoFrame> sink) {
        this(sink, "auto");
    }

    public FfmpegVideoDecoder(Consumer<VideoFrame> sink, String decoderMode) {
        super(false);
        this.sink = sink;
        this.decoderMode = normalizeMode(decoderMode);
        decodeThread = Thread.ofVirtual().name("scrcpy-video-decoder").start(this::decodeLoop);
    }

    private static String normalizeMode(String mode) {
        String value = Objects.requireNonNullElse(mode, "auto").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "hardware", "software" -> value;
            default -> "auto";
        };
    }

    private static int avId(CodecId c) {
        return switch (c) {
            case H264 -> AV_CODEC_ID_H264;
            case H265 -> AV_CODEC_ID_HEVC;
            case AV1 -> AV_CODEC_ID_AV1;
            default -> throw new IllegalArgumentException("Not video: " + c);
        };
    }

    private void init(CodecId codecId) {
        closeNative();

        AVCodec decoder = avcodec_find_decoder(avId(codecId));
        if (decoder == null || decoder.isNull()) {
            throw new IllegalStateException("decoder unavailable " + codecId);
        }

        if (!"software".equals(decoderMode)) {
            for (HardwareBackend backend : hardwareBackends()) {
                if (tryInitHardware(decoder, codecId, backend)) {
                    return;
                }
            }
            if ("hardware".equals(decoderMode)) {
                throw new IllegalStateException(
                        "hardware decoder unavailable for " + codecId + " on " + System.getProperty("os.name"));
            }
        }

        initSoftware(decoder, codecId);
    }

    private void initSoftware(AVCodec decoder, CodecId codecId) {
        ctx = avcodec_alloc_context3(decoder);
        if (ctx == null || ctx.isNull() || avcodec_open2(ctx, decoder, (AVDictionary) null) < 0) {
            closeNative();
            throw new IllegalStateException("cannot open software decoder " + codecId);
        }
        allocateFramesAndPacket();
        System.out.printf("[scrcpy-java] video decoder: software (%s)%n", codecId);
    }

    private boolean tryInitHardware(AVCodec decoder, CodecId codecId, HardwareBackend backend) {
        AVCodecHWConfig selected = null;
        for (int i = 0; ; i++) {
            AVCodecHWConfig config = avcodec_get_hw_config(decoder, i);
            if (config == null || config.isNull()) break;
            if ((config.methods() & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0
                    && config.device_type() == backend.type()) {
                selected = config;
                break;
            }
        }
        if (selected == null) return false;

        AVBufferRef device = new AVBufferRef(null);
        int createResult = av_hwdevice_ctx_create(
                device, backend.type(), (String) null, (AVDictionary) null, 0);
        if (createResult < 0 || device.isNull()) {
            if (!device.isNull()) av_buffer_unref(device);
            return false;
        }

        AVCodecContext candidate = avcodec_alloc_context3(decoder);
        if (candidate == null || candidate.isNull()) {
            av_buffer_unref(device);
            return false;
        }

        int selectedPixelFormat = selected.pix_fmt();
        AVCodecContext.Get_format_AVCodecContext_IntPointer callback =
                new AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                    @Override
                    public int call(AVCodecContext ignored, IntPointer formats) {
                        for (long i = 0; ; i++) {
                            int format = formats.get(i);
                            if (format == AV_PIX_FMT_NONE) break;
                            if (format == selectedPixelFormat) return format;
                        }
                        return AV_PIX_FMT_NONE;
                    }
                };

        candidate.get_format(callback);
        candidate.hw_device_ctx(av_buffer_ref(device));
        candidate.hwaccel_flags(candidate.hwaccel_flags() | AV_HWACCEL_FLAG_IGNORE_LEVEL);

        if (avcodec_open2(candidate, decoder, (AVDictionary) null) < 0) {
            avcodec_free_context(candidate);
            av_buffer_unref(device);
            callback.close();
            return false;
        }

        ctx = candidate;
        hwDevice = device;
        hwPixelFormat = selectedPixelFormat;
        getFormatCallback = callback;
        activeHardware = backend.name();
        allocateFramesAndPacket();
        System.out.printf("[scrcpy-java] video decoder: hardware %s (%s)%n", activeHardware, codecId);
        return true;
    }

    private void allocateFramesAndPacket() {
        frame = av_frame_alloc();
        softwareFrame = av_frame_alloc();
        rgb = av_frame_alloc();
        packet = av_packet_alloc();
        if (frame == null || softwareFrame == null || rgb == null || packet == null
                || frame.isNull() || softwareFrame.isNull() || rgb.isNull() || packet.isNull()) {
            closeNative();
            throw new IllegalStateException("cannot allocate FFmpeg video frames");
        }
    }

    private static List<HardwareBackend> hardwareBackends() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of(new HardwareBackend("d3d11va", AV_HWDEVICE_TYPE_D3D11VA));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of(new HardwareBackend("videotoolbox", AV_HWDEVICE_TYPE_VIDEOTOOLBOX));
        }
        if (os.contains("linux")) {
            // VAAPI covers Intel/AMD on the normal Linux graphics stack.
            // CUDA/NVDEC is attempted next for NVIDIA systems.
            return List.of(
                    new HardwareBackend("vaapi", AV_HWDEVICE_TYPE_VAAPI),
                    new HardwareBackend("cuda", AV_HWDEVICE_TYPE_CUDA));
        }
        return List.of();
    }

    private record HardwareBackend(String name, int type) {}

    private void converter(int w, int h, int fmt) {
        if (w == width && h == height && fmt == pixelFormat && sws != null) return;

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
            throw new IllegalStateException("cannot create swscale context: " + w + "x" + h + " fmt=" + fmt);
        }

        av_frame_unref(rgb);
        rgb.width(w);
        rgb.height(h);
        rgb.format(AV_PIX_FMT_BGRA);
        if (av_frame_get_buffer(rgb, 32) < 0) {
            throw new IllegalStateException("cannot allocate BGRA frame buffer: " + w + "x" + h);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ch, MediaMessage m) {
        if (closed.get()) {
            releaseMessage(m);
            return;
        }
        if (!decodeQueue.offer(m)) {
            MediaMessage dropped = decodeQueue.poll();
            releaseMessage(dropped);
            if (!decodeQueue.offer(m)) releaseMessage(m);
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
            if (!DEBUG_SKIP_DECODE) init(c.codec());
            return;
        }
        if (!(m instanceof MediaMessage.Packet p) || p.config()) return;
        if (DEBUG_SKIP_DECODE) return;

        byte[] b = new byte[p.data().readableBytes()];
        int payloadSize = b.length;
        p.data().getBytes(p.data().readerIndex(), b);

        av_packet_unref(packet);
        if (av_new_packet(packet, payloadSize) < 0) return;
        packet.data().put(b, 0, payloadSize);
        packet.pts(p.pts()).dts(p.pts());

        if (avcodec_send_packet(ctx, packet) < 0) return;
        while (avcodec_receive_frame(ctx, frame) >= 0) {
            if (DEBUG_SKIP_RENDER) continue;

            AVFrame source = frame;
            if (activeHardware != null && frame.format() == hwPixelFormat) {
                av_frame_unref(softwareFrame);
                if (av_hwframe_transfer_data(softwareFrame, frame, 0) < 0) {
                    System.err.printf("[scrcpy-java] %s frame transfer failed; dropping frame%n", activeHardware);
                    continue;
                }
                source = softwareFrame;
            }

            converter(source.width(), source.height(), source.format());
            if (DEBUG_SKIP_SWS_SCALE) continue;

            int scaled = sws_scale(
                    sws,
                    source.data(),
                    source.linesize(),
                    0,
                    source.height(),
                    rgb.data(),
                    rgb.linesize());
            if (scaled <= 0) continue;

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
        if (m instanceof MediaMessage.Packet p) ReferenceCountUtil.release(p.data());
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
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
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        if (rgb != null) {
            av_frame_free(rgb);
            rgb = null;
        }
        if (softwareFrame != null) {
            av_frame_free(softwareFrame);
            softwareFrame = null;
        }
        if (frame != null) {
            av_frame_free(frame);
            frame = null;
        }
        if (ctx != null) {
            avcodec_free_context(ctx);
            ctx = null;
        }
        if (hwDevice != null && !hwDevice.isNull()) {
            av_buffer_unref(hwDevice);
            hwDevice = null;
        }
        if (getFormatCallback != null) {
            getFormatCallback.close();
            getFormatCallback = null;
        }
        hwPixelFormat = AV_PIX_FMT_NONE;
        activeHardware = null;
        width = height = 0;
        pixelFormat = -1;
    }
}
