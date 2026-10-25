package com.jian.scrcpy.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Identifies a reverse-tunnel socket from its first stream bytes.
 *
 * <p>The scrcpy server opens video, audio and control connections from
 * independent threads, so TCP accept order is not a reliable stream identity.
 * Video/audio sockets expose a 4-byte codec id; the control socket does not.</p>
 */
final class ScrcpyStreamClassifier extends ByteToMessageDecoder {
    static final int VIDEO_H264 = 0x68323634;
    static final int VIDEO_H265 = 0x68323635;
    static final int VIDEO_AV1 = 0x00617631;
    static final int VIDEO_VP8 = 0x00767038;
    static final int VIDEO_VP9 = 0x00767039;

    static final int AUDIO_OPUS = 0x6f707573;
    static final int AUDIO_AAC = 0x00616163;
    static final int AUDIO_FLAC = 0x666c6163;
    static final int AUDIO_RAW = 0x00726177;

    private final IntConsumer classifier;

    ScrcpyStreamClassifier(IntConsumer classifier) {
        this.classifier = classifier;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }

        int id = in.getInt(in.readerIndex());
        if (isVideoCodec(id)) {
            classifier.accept(id);
            return;
        }
        if (isAudioCodec(id)) {
            classifier.accept(id);
            return;
        }

        // A control socket has no codec header. It may remain idle, so the
        // connection owner assigns it once all enabled media sockets are known.
        classifier.accept(-1);
    }

    static boolean isVideoCodec(int id) {
        return id == VIDEO_H264 || id == VIDEO_H265 || id == VIDEO_AV1
                || id == VIDEO_VP8 || id == VIDEO_VP9;
    }

    static boolean isAudioCodec(int id) {
        return id == AUDIO_OPUS || id == AUDIO_AAC || id == AUDIO_FLAC || id == AUDIO_RAW;
    }
}
