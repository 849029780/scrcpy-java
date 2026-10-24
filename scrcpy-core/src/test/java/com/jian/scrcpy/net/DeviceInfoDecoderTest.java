package com.jian.scrcpy.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DeviceInfoDecoderTest {

    @Test
    void reads64ByteName() {
        byte[] b = new byte[64];
        byte[] name = "Pixel 9".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, b, 0, name.length);

        var channel = new EmbeddedChannel(new DeviceInfoDecoder());

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(b)));

        var info = (DeviceInfoDecoder.DeviceInfo) channel.readInbound();
        assertEquals("Pixel 9", info.name());

        channel.finishAndReleaseAll();
    }

    @Test
    void forwardsBytesAfterMetadata() {
        byte[] metadata = new byte[64];
        byte[] name = "Pixel 9".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, metadata, 0, name.length);

        var channel = new EmbeddedChannel(new DeviceInfoDecoder());

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(
                metadata,
                new byte[]{0x68, 0x32, 0x36, 0x34})));

        var info = (DeviceInfoDecoder.DeviceInfo) channel.readInbound();
        assertEquals("Pixel 9", info.name());

        ByteBuf remaining = channel.readInbound();
        assertNotNull(remaining);
        assertEquals(4, remaining.readableBytes());
        assertEquals(0x68, remaining.readUnsignedByte());
        remaining.release();

        channel.finishAndReleaseAll();
    }
}
