package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class DeviceMessageDecoder extends ByteToMessageDecoder {
    protected void decode(ChannelHandlerContext c, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) return;
        in.markReaderIndex();
        int t = in.readUnsignedByte();
        switch (t) {
            case 0 -> {
                if (in.readableBytes() < 4) {
                    in.resetReaderIndex();
                    return;
                }
                int n = in.readInt();
                if (n < 0 || in.readableBytes() < n) {
                    in.resetReaderIndex();
                    return;
                }
                out.add(new DeviceMessage.Clipboard(in.readCharSequence(n, StandardCharsets.UTF_8).toString()));
            }
            case 1 -> {
                if (in.readableBytes() < 8) {
                    in.resetReaderIndex();
                    return;
                }
                out.add(new DeviceMessage.AckClipboard(in.readLong()));
            }
            case 2 -> {
                if (in.readableBytes() < 4) {
                    in.resetReaderIndex();
                    return;
                }
                int id = in.readUnsignedShort(), n = in.readUnsignedShort();
                if (in.readableBytes() < n) {
                    in.resetReaderIndex();
                    return;
                }
                byte[] b = new byte[n];
                in.readBytes(b);
                out.add(new DeviceMessage.UhidOutput(id, b));
            }
            default -> throw new IllegalStateException("unknown device message " + t);
        }
    }
}