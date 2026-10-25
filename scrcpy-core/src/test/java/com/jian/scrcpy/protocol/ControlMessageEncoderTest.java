package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static com.jian.scrcpy.protocol.ControlMessages.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ControlMessageEncoderTest {
    private static byte[] encode(Message message) {
        EmbeddedChannel channel = new EmbeddedChannel(new ControlMessageEncoder());
        assertTrue(channel.writeOutbound(message));
        ByteBuf buf = channel.readOutbound();
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void keycodeMatchesUpstreamVector() {
        assertArrayEquals(new byte[]{
                0, 1,
                0, 0, 0, 0x42,
                0, 0, 0, 5,
                0, 0, 0, 0x41
        }, encode(new Keycode(1, 0x42, 5, 0x41)));
    }

    @Test
    void touchMatchesUpstreamVector() {
        assertArrayEquals(new byte[]{
                2, 0,
                0x12, 0x34, 0x56, 0x78, (byte) 0x87, 0x65, 0x43, 0x21,
                0, 0, 0, 0x64, 0, 0, 0, (byte) 0xc8,
                0x04, 0x38, 0x07, (byte) 0x80,
                (byte) 0xff, (byte) 0xff,
                0, 0, 0, 1,
                0, 0, 0, 1
        }, encode(new Touch(0, 0x1234567887654321L,
                new Position(100, 200, 1080, 1920), 1f, 1, 1)));
    }

    @Test
    void scrollUsesSignedFixedPointExactlyLikeScrcpy() {
        assertArrayEquals(new byte[]{
                3,
                0, 0, 1, 4, 0, 0, 4, 2,
                4, 0x38, 7, (byte) 0x80,
                0x7f, (byte) 0xff,
                (byte) 0x80, 0,
                0, 0, 0, 1
        }, encode(new Scroll(new Position(260, 1026, 1080, 1920), 16f, -16f, 1)));
    }

    @Test
    void clipboardAndBackHaveDedicatedPayloads() {
        assertArrayEquals(new byte[]{4, 1}, encode(new BackOrScreenOn(1)));
        assertArrayEquals(new byte[]{8, 1}, encode(new GetClipboard(COPY_KEY_COPY)));
    }

    @Test
    void uhidCreateMatchesUpstreamVector() {
        assertArrayEquals(new byte[]{
                12, 0, 42, 0x12, 0x34, 0x56, 0x78,
                3, 'A', 'B', 'C',
                0, 11,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
        }, encode(new UhidCreate(42, 0x1234, 0x5678, "ABC",
                new byte[]{1,2,3,4,5,6,7,8,9,10,11})));
    }

    @Test
    void cameraTorchCarriesBooleanPayload() {
        assertArrayEquals(new byte[]{18, 1}, encode(new CameraSetTorch(true)));
    }
}
