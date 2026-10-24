package com.jian.scrcpy.net;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardProbeDecoderTest {

    @Test
    void consumesZeroProbe() {
        var channel = new EmbeddedChannel(new ForwardProbeDecoder());

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0})));

        channel.finishAndReleaseAll();
    }

    @Test
    void acceptsNonZeroProbe() {
        var channel = new EmbeddedChannel(new ForwardProbeDecoder());

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{1})));

        channel.finishAndReleaseAll();
    }

    @Test
    void forwardsBytesAfterProbe() {
        var channel = new EmbeddedChannel(new ForwardProbeDecoder());

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0, 0x12})));
        var forwarded = channel.readInbound();

        assertTrue(forwarded instanceof io.netty.buffer.ByteBuf);
        var buffer = (io.netty.buffer.ByteBuf) forwarded;
        assertTrue(buffer.readableBytes() == 1);
        assertTrue(buffer.readUnsignedByte() == 0x12);
        buffer.release();

        channel.finishAndReleaseAll();
    }
}