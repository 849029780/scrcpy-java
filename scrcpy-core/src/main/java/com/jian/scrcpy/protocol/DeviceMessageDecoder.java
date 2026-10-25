package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes scrcpy 5.0.1 device messages (server -> client) from the control socket.
 *
 * <p>The decoder is fully streaming-safe: a message may be split across any
 * number of TCP packets, and several messages may be coalesced in one packet.</p>
 */
public final class DeviceMessageDecoder extends ByteToMessageDecoder {
    public static final int MAX_MESSAGE_SIZE = 1 << 18;
    public static final int MAX_CLIPBOARD_TEXT_LENGTH = MAX_MESSAGE_SIZE - 5;

    private static final int TYPE_CLIPBOARD = 0;
    private static final int TYPE_ACK_CLIPBOARD = 1;
    private static final int TYPE_UHID_OUTPUT = 2;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            return;
        }

        int start = in.readerIndex();
        int type = in.readUnsignedByte();

        switch (type) {
            case TYPE_CLIPBOARD -> decodeClipboard(in, out, start);
            case TYPE_ACK_CLIPBOARD -> decodeAckClipboard(in, out, start);
            case TYPE_UHID_OUTPUT -> decodeUhidOutput(in, out, start);
            default -> throw new CorruptedFrameException("Unknown scrcpy device message type: " + type);
        }
    }

    private static void decodeClipboard(ByteBuf in, List<Object> out, int start) {
        if (in.readableBytes() < Integer.BYTES) {
            in.readerIndex(start);
            return;
        }

        long length = in.readUnsignedInt();
        if (length > MAX_CLIPBOARD_TEXT_LENGTH) {
            throw new CorruptedFrameException(
                    "scrcpy clipboard message too large: " + length + " > " + MAX_CLIPBOARD_TEXT_LENGTH);
        }
        if (in.readableBytes() < length) {
            in.readerIndex(start);
            return;
        }

        int n = (int) length;
        String text = in.toString(in.readerIndex(), n, StandardCharsets.UTF_8);
        in.skipBytes(n);
        out.add(new DeviceMessage.Clipboard(text));
    }

    private static void decodeAckClipboard(ByteBuf in, List<Object> out, int start) {
        if (in.readableBytes() < Long.BYTES) {
            in.readerIndex(start);
            return;
        }
        out.add(new DeviceMessage.AckClipboard(in.readLong()));
    }

    private static void decodeUhidOutput(ByteBuf in, List<Object> out, int start) {
        if (in.readableBytes() < 4) {
            in.readerIndex(start);
            return;
        }

        int id = in.readUnsignedShort();
        int length = in.readUnsignedShort();
        if (in.readableBytes() < length) {
            in.readerIndex(start);
            return;
        }

        byte[] data = new byte[length];
        in.readBytes(data);
        out.add(new DeviceMessage.UhidOutput(id, data));
    }
}
