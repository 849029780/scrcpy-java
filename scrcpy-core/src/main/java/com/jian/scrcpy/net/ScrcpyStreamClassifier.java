package com.jian.scrcpy.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Identifies a scrcpy reverse-tunnel socket from its stream bytes.
 *
 * <p>Do not rely on TCP accept order: video/audio/control sockets may connect
 * in different orders when several scrcpy sessions are started concurrently.</p>
 *
 * <p>scrcpy video starts with a 64-byte device-name field, audio starts with a
 * 4-byte codec id, and control has no codec header and starts with a device
 * message type when the server sends one.</p>
 */
final class ScrcpyStreamClassifier extends ByteToMessageDecoder {
    enum StreamType { VIDEO, AUDIO, CONTROL }

    static final int VIDEO_H264 = 0x68323634;
    static final int VIDEO_H265 = 0x68323635;
    static final int VIDEO_AV1 = 0x00617631;
    static final int VIDEO_VP8 = 0x00767038;
    static final int VIDEO_VP9 = 0x00767039;

    static final int AUDIO_OPUS = 0x6f707573;
    static final int AUDIO_AAC = 0x00616163;
    static final int AUDIO_FLAC = 0x666c6163;
    static final int AUDIO_RAW = 0x00726177;

    private static final int DEVICE_INFO_SIZE = 64;

    private final BiFunction<ChannelHandlerContext, StreamType, Boolean> classifier;

    ScrcpyStreamClassifier(BiFunction<ChannelHandlerContext, StreamType, Boolean> classifier) {
        this.classifier = classifier;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) return;

        StreamType type = identify(in);
        if (type == null) return;

        if (!Boolean.TRUE.equals(classifier.apply(ctx, type))) return;

        // Preserve every byte already received, including the protocol header.
        if (in.isReadable()) {
            out.add(in.readRetainedSlice(in.readableBytes()));
        }
        ctx.pipeline().remove(this);
    }

    private static StreamType identify(ByteBuf in) {
        int readable = in.readableBytes();

        if (readable >= 4) {
            int id = in.getInt(in.readerIndex());
            if (isAudioCodec(id)) return StreamType.AUDIO;
            if (isVideoCodec(id)) return StreamType.VIDEO;
        }

        // scrcpy 5.0.1 device messages use types 0..2 on the server->client
        // control stream. Video device names are printable UTF-8 instead.
        int first = in.getUnsignedByte(in.readerIndex());
        if (first <= 2) return StreamType.CONTROL;

        // The video socket starts with the complete 64-byte device name.
        if (readable >= DEVICE_INFO_SIZE && looksLikeDeviceInfo(in, in.readerIndex())) {
            return StreamType.VIDEO;
        }

        return null;
    }

    private static boolean looksLikeDeviceInfo(ByteBuf in, int index) {
        byte[] bytes = new byte[DEVICE_INFO_SIZE];
        in.getBytes(index, bytes);

        int end = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                end = i;
                break;
            }
        }
        if (end <= 0) return false;

        try {
            String name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end))
                    .toString();
            if (name.isBlank()) return false;

            for (int i = 0; i < name.length(); i++) {
                if (Character.isISOControl(name.charAt(i))) return false;
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static boolean isVideoCodec(int id) {
        return id == VIDEO_H264 || id == VIDEO_H265 || id == VIDEO_AV1
                || id == VIDEO_VP8 || id == VIDEO_VP9;
    }

    static boolean isAudioCodec(int id) {
        return id == AUDIO_OPUS || id == AUDIO_AAC || id == AUDIO_FLAC || id == AUDIO_RAW;
    }
}