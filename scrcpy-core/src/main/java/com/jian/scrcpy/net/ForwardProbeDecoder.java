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
            if (in.isReadable()) out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }
        if (!in.isReadable()) return;
        int probe = in.readUnsignedByte();
        if (probe != 0) throw new IllegalStateException("Invalid scrcpy forward probe: " + probe);
        done = true;
        ctx.pipeline().remove(this);
    }
}