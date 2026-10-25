package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigPacketMergerTest {

    @Test
    void prependsH264Config() {
        var c = new EmbeddedChannel(new ConfigPacketMerger());
        c.writeInbound(new MediaMessage.Codec(CodecId.H264));
        assertInstanceOf(MediaMessage.Codec.class, c.readInbound());

        c.writeInbound(new MediaMessage.Packet(-1, true, false,
                Unpooled.wrappedBuffer(new byte[]{1, 2})));
        c.writeInbound(new MediaMessage.Packet(10, false, true,
                Unpooled.wrappedBuffer(new byte[]{3})));

        var p = (MediaMessage.Packet) c.readInbound();
        assertArrayEquals(new byte[]{1, 2, 3}, ByteBufUtil.getBytes(p.data()));
        p.data().release();
        assertNull(c.readInbound());
        c.finishAndReleaseAll();
    }

    @Test
    void prependsH265Config() {
        var c = new EmbeddedChannel(new ConfigPacketMerger());
        c.writeInbound(new MediaMessage.Codec(CodecId.H265));
        assertInstanceOf(MediaMessage.Codec.class, c.readInbound());

        c.writeInbound(new MediaMessage.Packet(-1, true, false,
                Unpooled.wrappedBuffer(new byte[]{1, 2})));
        c.writeInbound(new MediaMessage.Packet(10, false, true,
                Unpooled.wrappedBuffer(new byte[]{3})));

        var p = (MediaMessage.Packet) c.readInbound();
        assertArrayEquals(new byte[]{1, 2, 3}, ByteBufUtil.getBytes(p.data()));
        p.data().release();
        assertNull(c.readInbound());
        c.finishAndReleaseAll();
    }

    @Test
    void doesNotMergeAv1Config() {
        var c = new EmbeddedChannel(new ConfigPacketMerger());
        c.writeInbound(new MediaMessage.Codec(CodecId.AV1));
        assertInstanceOf(MediaMessage.Codec.class, c.readInbound());

        c.writeInbound(new MediaMessage.Packet(-1, true, false,
                Unpooled.wrappedBuffer(new byte[]{1, 2})));
        c.writeInbound(new MediaMessage.Packet(10, false, true,
                Unpooled.wrappedBuffer(new byte[]{3})));

        var config = (MediaMessage.Packet) c.readInbound();
        assertArrayEquals(new byte[]{1, 2}, ByteBufUtil.getBytes(config.data()));
        config.data().release();

        var packet = (MediaMessage.Packet) c.readInbound();
        assertArrayEquals(new byte[]{3}, ByteBufUtil.getBytes(packet.data()));
        packet.data().release();

        c.finishAndReleaseAll();
    }
}
