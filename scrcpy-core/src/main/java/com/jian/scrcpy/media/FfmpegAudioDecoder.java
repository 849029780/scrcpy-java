package com.jian.scrcpy.media;

import com.jian.scrcpy.protocol.*;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.javacpp.*;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

public final class FfmpegAudioDecoder extends SimpleChannelInboundHandler<MediaMessage> implements AutoCloseable {
    private final Consumer<AudioFrame> sink;
    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket packet;
    private CodecId codec;
    /** AAC requires the codec configuration (AudioSpecificConfig) before opening the decoder. */
    private byte[] pendingCodecConfig;
    private final BlockingQueue<MediaMessage> decodeQueue = new ArrayBlockingQueue<>(240);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread decodeThread;

    public FfmpegAudioDecoder(Consumer<AudioFrame> s) {
        super(false);
        sink = s;
        decodeThread = Thread.ofVirtual().name("scrcpy-audio-decoder").start(this::decodeLoop);
    }

    private static int avId(CodecId c) {
        return switch (c) {
            case OPUS -> AV_CODEC_ID_OPUS;
            case AAC -> AV_CODEC_ID_AAC;
            case FLAC -> AV_CODEC_ID_FLAC;
            default -> throw new IllegalArgumentException("Not an FFmpeg audio codec: " + c);
        };
    }

    private void init(CodecId codec) {
        // A decoder instance should normally receive exactly one Codec message.
        // Still, make re-initialization safe so a repeated Codec message cannot
        // leak the previous native decoder state.
        closeNative();

        this.codec = codec;
        this.pendingCodecConfig = null;

        AVCodec dec = avcodec_find_decoder(avId(codec));
        if (dec == null) throw new IllegalStateException("decoder unavailable: " + codec);
        ctx = avcodec_alloc_context3(dec);
        if (ctx == null) throw new IllegalStateException("cannot allocate decoder: " + codec);

        // AAC carries its AudioSpecificConfig as a separate scrcpy config packet.
        // Do not open the decoder before that packet is received.
        if (codec != CodecId.AAC) {
            openDecoder(dec);
        }
    }

    private void openDecoder(AVCodec dec) {
        if (ctx == null) throw new IllegalStateException("decoder context is null: " + codec);
        if (codec == CodecId.AAC && pendingCodecConfig != null) {
            int padding = AV_INPUT_BUFFER_PADDING_SIZE;
            Pointer extra = av_mallocz(pendingCodecConfig.length + padding);
            if (extra == null) throw new IllegalStateException("cannot allocate AAC extradata");
            new BytePointer(extra).put(pendingCodecConfig);
            ctx.extradata(new BytePointer(extra));
            ctx.extradata_size(pendingCodecConfig.length);
        }
        if (avcodec_open2(ctx, dec, (AVDictionary) null) < 0)
            throw new IllegalStateException("cannot open decoder: " + codec);

        frame = av_frame_alloc();
        packet = av_packet_alloc();
        if (frame == null || packet == null) throw new IllegalStateException("cannot allocate audio decoder state");

        System.out.println("[scrcpy-java] audio decoder initialized: " + codec
                + (codec == CodecId.AAC ? " config=" + pendingCodecConfig.length + " bytes" : "")
                + " ffmpeg=" + org.bytedeco.ffmpeg.global.avutil.av_version_info().getString());
        System.out.flush();
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
            System.err.println("[scrcpy-java] audio decode worker failed: " + t);
            t.printStackTrace(System.err);
        } finally {
            MediaMessage m;
            while ((m = decodeQueue.poll()) != null) releaseMessage(m);
            // Native FFmpeg state is owned and freed by the decode worker.
            // This prevents close() from freeing it while decodeMessage() is
            // still executing on this thread.
            closeNative();
        }
    }

    private synchronized void decodeMessage(MediaMessage m) {
        if (m instanceof MediaMessage.Codec c) {
            init(c.codec());
            return;
        }
        if (!(m instanceof MediaMessage.Packet p)) return;
        if (p.config()) {
            if (codec == CodecId.AAC && ctx != null && frame == null) {
                pendingCodecConfig = new byte[p.data().readableBytes()];
                p.data().getBytes(p.data().readerIndex(), pendingCodecConfig);
                AVCodec dec = avcodec_find_decoder(avId(codec));
                if (dec == null) throw new IllegalStateException("decoder unavailable: " + codec);
                openDecoder(dec);
            }
            return;
        }
        if (ctx == null || packet == null) {
            // AAC must have its config packet before media packets can be decoded.
            return;
        }
        byte[] bytes = new byte[p.data().readableBytes()];
        int payloadSize = bytes.length;
        p.data().getBytes(p.data().readerIndex(), bytes);
        av_packet_unref(packet);
        int allocRet = av_new_packet(packet, payloadSize);
        if (allocRet < 0) {
            logDecodeError("av_new_packet", allocRet, payloadSize, p.pts());
            return;
        }

        packet.data().put(bytes, 0, payloadSize);
        packet.pts(p.pts()).dts(p.pts());

        int ret = avcodec_send_packet(ctx, packet);
            if (ret == AVERROR_EAGAIN()) {
                drainFrames(p.pts());
                ret = avcodec_send_packet(ctx, packet);
            }
            if (ret < 0) {
                logDecodeError("send_packet", ret, payloadSize, p.pts());
                return;
            }
            drainFrames(p.pts());
    }

    private static void releaseMessage(MediaMessage m) {
        if (m instanceof MediaMessage.Packet p) ReferenceCountUtil.release(p.data());
    }

    private void drainFrames(long pts) {
        for (;;) {
            int ret = avcodec_receive_frame(ctx, frame);
            if (ret >= 0) {
                AudioFrame pcm = toPcm(frame, pts);
                if (pcm != null) {
                    sink.accept(pcm);
                }
                continue;
            }
            if (ret != AVERROR_EAGAIN() && ret != AVERROR_EOF) {
                logDecodeError("receive_frame", ret, packet == null ? 0 : packet.size(), pts);
            }
            return;
        }
    }

    private void logDecodeError(String operation, int error, long size, long pts) {
        try (BytePointer errorText = new BytePointer(256)) {
            av_strerror(error, errorText, 256);
            System.err.printf("[scrcpy-java] audio %s failed: codec=%s size=%d pts=%d error=%d (%s)%n",
                    operation, codec, size, pts, error, errorText.getString());
        }
    }

    private AudioFrame toPcm(AVFrame f, long pts) {
        int channels = f.ch_layout().nb_channels();
        int samples = f.nb_samples();
        int sampleRate = f.sample_rate();
        if (channels <= 0 || samples <= 0 || sampleRate <= 0) return null;
        int format = f.format();
        short[] pcm = new short[samples * channels];

        if (format == AV_SAMPLE_FMT_FLT) {
            FloatPointer src = new FloatPointer(f.data(0));
            for (int i = 0; i < pcm.length; i++) pcm[i] = floatToS16(src.get(i));
        } else if (format == AV_SAMPLE_FMT_FLTP) {
            for (int ch = 0; ch < channels; ch++) {
                FloatPointer src = new FloatPointer(f.data(ch));
                for (int i = 0; i < samples; i++) pcm[i * channels + ch] = floatToS16(src.get(i));
            }
        } else if (format == AV_SAMPLE_FMT_S16) {
            ShortPointer src = new ShortPointer(f.data(0));
            for (int i = 0; i < pcm.length; i++) pcm[i] = src.get(i);
        } else if (format == AV_SAMPLE_FMT_S16P) {
            for (int ch = 0; ch < channels; ch++) {
                ShortPointer src = new ShortPointer(f.data(ch));
                for (int i = 0; i < samples; i++) pcm[i * channels + ch] = src.get(i);
            }
        } else {
            System.err.println("[scrcpy-java] unsupported decoded audio sample format: " + format);
            return null;
        }
        return new AudioFrame(sampleRate, channels, pcm, pts);
    }

    private static short floatToS16(float value) {
        float v = Math.max(-1f, Math.min(1f, value));
        return (short) Math.round(v * 32767f);
    }

    @Override public void close() {
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
        if (packet != null) { av_packet_free(packet); packet = null; }
        if (frame != null) { av_frame_free(frame); frame = null; }
        if (ctx != null) { avcodec_free_context(ctx); ctx = null; }
        pendingCodecConfig = null;
    }
}
