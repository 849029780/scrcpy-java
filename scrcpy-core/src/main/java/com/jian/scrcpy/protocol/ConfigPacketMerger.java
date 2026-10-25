package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * Prepends MediaCodec configuration data to the first media packet.
 *
 * scrcpy requires this only for H.264/H.265 (H.26x). Other codecs must keep
 * their config packets independent, matching the upstream demuxer behavior.
 */
public final class ConfigPacketMerger extends ChannelInboundHandlerAdapter {
    private boolean mergeConfig;
    private ByteBuf config;

    @Override
    public void channelRead(ChannelHandlerContext c, Object m) throws Exception {
        if (m instanceof MediaMessage.Codec codec) {
            mergeConfig = codec.codec() == CodecId.H264 || codec.codec() == CodecId.H265;
            releaseConfig();
            c.fireChannelRead(m);
            return;
        }

        if (!(m instanceof MediaMessage.Packet p)) {
            c.fireChannelRead(m);
            return;
        }

        if (!mergeConfig) {
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
            releaseConfig();
            ReferenceCountUtil.release(p.data());
            c.fireChannelRead(new MediaMessage.Packet(
                    p.pts(), false, p.keyFrame(), merged));
        } else {
            c.fireChannelRead(m);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext c) {
        releaseConfig();
    }

    private void releaseConfig() {
        ReferenceCountUtil.release(config);
        config = null;
    }
}
