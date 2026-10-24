package com.jian.scrcpy.media;

import com.jian.scrcpy.protocol.*;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.javacpp.*;

import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

public final class FfmpegAudioDecoder extends SimpleChannelInboundHandler<MediaMessage> implements AutoCloseable {
    private final Consumer<AudioFrame> sink;
    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket packet;

    public FfmpegAudioDecoder(Consumer<AudioFrame> s) { super(false); sink = s; }

    private static int avId(CodecId c) {
        return switch (c) {
            case OPUS -> AV_CODEC_ID_OPUS;
            case AAC -> AV_CODEC_ID_AAC;
            case FLAC -> AV_CODEC_ID_FLAC;
            default -> throw new IllegalArgumentException("Not an FFmpeg audio codec: " + c);
        };
    }

    private void init(CodecId codec) {
        AVCodec dec = avcodec_find_decoder(avId(codec));
        if (dec == null) throw new IllegalStateException("decoder unavailable: " + codec);
        ctx = avcodec_alloc_context3(dec);
        if (ctx == null || avcodec_open2(ctx, dec, (AVDictionary) null) < 0)
            throw new IllegalStateException("cannot open decoder: " + codec);
        frame = av_frame_alloc();
        packet = av_packet_alloc();
        if (frame == null || packet == null) throw new IllegalStateException("cannot allocate audio decoder state");
        System.out.println("[scrcpy-java] audio decoder initialized: " + codec);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ch, MediaMessage m) {
        if (m instanceof MediaMessage.Codec c) { init(c.codec()); return; }
        if (!(m instanceof MediaMessage.Packet p)) return;
        try {
            if (p.config()) return;
            byte[] bytes = new byte[p.data().readableBytes()];
            p.data().getBytes(p.data().readerIndex(), bytes);
            try (BytePointer bp = new BytePointer(bytes)) {
                av_packet_unref(packet);
                packet.data(bp).size(bytes.length).pts(p.pts());
                int ret = avcodec_send_packet(ctx, packet);
                if (ret < 0) {
                    System.err.printf("[scrcpy-java] audio packet decode failed: %d%n", ret);
                    return;
                }
                while ((ret = avcodec_receive_frame(ctx, frame)) >= 0) {
                    AudioFrame pcm = toPcm(frame, p.pts());
                    if (pcm != null) sink.accept(pcm);
                }
            }
        } finally {
            ReferenceCountUtil.release(p.data());
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
        if (packet != null) { av_packet_free(packet); packet = null; }
        if (frame != null) { av_frame_free(frame); frame = null; }
        if (ctx != null) { avcodec_free_context(ctx); ctx = null; }
    }
}
