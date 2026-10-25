package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ScrcpyAudioDecoderTest {
    private static final long CONFIG = 1L << 62;
    private static final long KEY = 1L << 61;

    @Test
    void parsesScrcpy501PacketFlags() {
        EmbeddedChannel channel = new EmbeddedChannel(new ScrcpyAudioDecoder());
        ByteBuf input = Unpooled.buffer();
        input.writeInt(0x6f707573); // opus
        input.writeLong(CONFIG);
        input.writeInt(3);
        input.writeBytes(new byte[]{1, 2, 3});
        long pts = 123456789L;
        input.writeLong(KEY | pts);
        input.writeInt(2);
        input.writeBytes(new byte[]{4, 5});

        assertTrue(channel.writeInbound(input));
        assertInstanceOf(MediaMessage.Codec.class, channel.readInbound());

        MediaMessage.Packet config = assertInstanceOf(MediaMessage.Packet.class, channel.readInbound());
        assertTrue(config.config());
        assertEquals(-1, config.pts());
        config.data().release();

        MediaMessage.Packet media = assertInstanceOf(MediaMessage.Packet.class, channel.readInbound());
        assertFalse(media.config());
        assertTrue(media.keyFrame());
        assertEquals(pts, media.pts());
        media.data().release();
        channel.finishAndReleaseAll();
    }
}
