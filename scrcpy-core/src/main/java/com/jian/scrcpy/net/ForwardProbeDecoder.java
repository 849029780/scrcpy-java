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

        // In scrcpy forward mode the server sends one dummy byte.
        // Its value is intentionally not part of the protocol contract;
        // the native client only checks that one byte can be received.
        int probe = in.readUnsignedByte();
        System.out.printf("[scrcpy-java] forward probe received: 0x%02x%n", probe);

        done = true;
        ctx.pipeline().remove(this);
    }
}
