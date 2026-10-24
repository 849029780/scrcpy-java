package com.jian.scrcpy.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public final class ForwardProbeDecoder extends ByteToMessageDecoder {
    private boolean done;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (done) {
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
            return;
        }
        if (!in.isReadable()) {
            return;
        }

        // In scrcpy forward mode the server sends one dummy byte before
        // device metadata. The byte value is intentionally irrelevant.
        int probe = in.readUnsignedByte();
        System.out.printf("[scrcpy-java] forward probe received: 0x%02x%n", probe);
        done = true;

        // The probe and device metadata may arrive in the same TCP read.
        // ByteToMessageDecoder stops after this handler is removed, so the
        // bytes already buffered after the probe must be explicitly forwarded.
        if (in.isReadable()) {
            out.add(in.readRetainedSlice(in.readableBytes()));
        }
        ctx.pipeline().remove(this);
    }
}
