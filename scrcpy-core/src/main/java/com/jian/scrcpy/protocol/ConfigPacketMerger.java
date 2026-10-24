package com.jian.scrcpy.protocol;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

public final class ConfigPacketMerger extends ChannelInboundHandlerAdapter {
    private ByteBuf config;

    public void channelRead(ChannelHandlerContext c, Object m) throws Exception {
        if (!(m instanceof MediaMessage.Packet p)) {
            c.fireChannelRead(m);
            return;
        }
        if (p.config()) {
            ReferenceCountUtil.release(config);
            config = p.data().retain();
            ReferenceCountUtil.release(p.data());
            return;
        }
        if (config != null) {
            ByteBuf merged = c.alloc().buffer(config.readableBytes() + p.data().readableBytes());
            merged.writeBytes(config, config.readerIndex(), config.readableBytes());
            merged.writeBytes(p.data(), p.data().readerIndex(), p.data().readableBytes());
            ReferenceCountUtil.release(config);
            config = null;
            ReferenceCountUtil.release(p.data());
            c.fireChannelRead(new MediaMessage.Packet(p.pts(), false, p.keyFrame(), merged));
        } else c.fireChannelRead(m);
    }

    public void handlerRemoved(ChannelHandlerContext c) {
        ReferenceCountUtil.release(config);
        config = null;
    }
}