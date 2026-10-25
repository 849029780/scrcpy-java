package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class DeviceMessageDecoderTest {
    @Test
    void clipboardMatchesUpstreamWireFormat() {
        EmbeddedChannel channel = new EmbeddedChannel(new DeviceMessageDecoder());
        byte[] text = "hello 世界".getBytes(StandardCharsets.UTF_8);
        assertTrue(channel.writeInbound(Unpooled.buffer().writeByte(0).writeInt(text.length).writeBytes(text)));
        assertEquals(new DeviceMessage.Clipboard("hello 世界"), channel.readInbound());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void ackClipboardMatchesUpstreamWireFormat() {
        EmbeddedChannel channel = new EmbeddedChannel(new DeviceMessageDecoder());
        assertTrue(channel.writeInbound(Unpooled.buffer().writeByte(1).writeLong(0x0102030405060708L)));
        assertEquals(new DeviceMessage.AckClipboard(0x0102030405060708L), channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void uhidOutputMatchesUpstreamWireFormat() {
        EmbeddedChannel channel = new EmbeddedChannel(new DeviceMessageDecoder());
        assertTrue(channel.writeInbound(Unpooled.buffer()
                .writeByte(2).writeShort(42).writeShort(3).writeBytes(new byte[]{1, 2, 3})));
        DeviceMessage.UhidOutput msg = assertInstanceOf(DeviceMessage.UhidOutput.class, channel.readInbound());
        assertEquals(42, msg.id());
        assertArrayEquals(new byte[]{1, 2, 3}, msg.data());
        channel.finishAndReleaseAll();
    }

    @Test
    void fragmentedMessageIsReassembledAcrossTcpReads() {
        EmbeddedChannel channel = new EmbeddedChannel(new DeviceMessageDecoder());
        byte[] text = "fragmented".getBytes(StandardCharsets.UTF_8);
        ByteBuf whole = Unpooled.buffer().writeByte(0).writeInt(text.length).writeBytes(text);
        byte[] bytes = new byte[whole.readableBytes()];
        whole.readBytes(bytes);
        whole.release();

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 0, 2))));
        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 2, 7))));
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 7, bytes.length))));
        assertEquals(new DeviceMessage.Clipboard("fragmented"), channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void coalescedMessagesAreDecodedIndependently() {
        EmbeddedChannel channel = new EmbeddedChannel(new DeviceMessageDecoder());
        ByteBuf buf = Unpooled.buffer()
                .writeByte(1).writeLong(7)
                .writeByte(2).writeShort(9).writeShort(2).writeBytes(new byte[]{10, 11});
        assertTrue(channel.writeInbound(buf));
        assertEquals(new DeviceMessage.AckClipboard(7), channel.readInbound());
        DeviceMessage.UhidOutput output = assertInstanceOf(DeviceMessage.UhidOutput.class, channel.readInbound());
        assertEquals(9, output.id());
        assertArrayEquals(new byte[]{10, 11}, output.data());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void oversizedClipboardIsRejectedInsteadOfWaitingForever() {
        EmbeddedChannel channel = new EmbeddedChannel(new DeviceMessageDecoder());
        CorruptedFrameException error = assertThrows(CorruptedFrameException.class,
                () -> channel.writeInbound(Unpooled.buffer().writeByte(0)
                        .writeInt(DeviceMessageDecoder.MAX_CLIPBOARD_TEXT_LENGTH + 1)));
        assertTrue(error.getMessage().contains("too large"));
        channel.finishAndReleaseAll();
    }

    @Test
    void unknownTypeIsRejectedAsUnrecoverable() {
        EmbeddedChannel channel = new EmbeddedChannel(new DeviceMessageDecoder());
        CorruptedFrameException error = assertThrows(CorruptedFrameException.class,
                () -> channel.writeInbound(Unpooled.buffer().writeByte(99)));
        assertTrue(error.getMessage().contains("Unknown"));
        channel.finishAndReleaseAll();
    }
}
