package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.jian.scrcpy.protocol.ControlMessages.*;
import static org.junit.jupiter.api.Assertions.*;

final class ControlProtocolCompletenessTest {
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
    void allScrcpy501ControlMessageTypesAreEncodable() {
        Message[] messages = {
                new Keycode(0, 4, 0, 0),
                new Text("abc"),
                new Touch(0, POINTER_ID_MOUSE, new Position(1, 2, 1080, 1920), 1f, 1, 1),
                new Scroll(new Position(1, 2, 1080, 1920), 1, -1, 0),
                new BackOrScreenOn(0),
                new Simple(ControlMessageType.EXPAND_NOTIFICATION_PANEL),
                new Simple(ControlMessageType.EXPAND_SETTINGS_PANEL),
                new Simple(ControlMessageType.COLLAPSE_PANELS),
                new GetClipboard(COPY_KEY_NONE),
                new ClipboardSet(7, false, "clip"),
                new DisplayPower(true),
                new Simple(ControlMessageType.ROTATE_DEVICE),
                new UhidCreate(1, 2, 3, "kbd", new byte[]{1, 2}),
                new UhidInput(1, new byte[]{3, 4}),
                new UhidDestroy(1),
                new Simple(ControlMessageType.OPEN_HARD_KEYBOARD_SETTINGS),
                new StartApp("com.example.app"),
                new Simple(ControlMessageType.RESET_VIDEO),
                new CameraSetTorch(true),
                new Simple(ControlMessageType.CAMERA_ZOOM_IN),
                new Simple(ControlMessageType.CAMERA_ZOOM_OUT),
                new ResizeDisplay(800, 600),
                new ScanFile("/sdcard/a.jpg")
        };

        assertEquals(ControlMessageType.values().length, messages.length);
        for (int i = 0; i < messages.length; i++) {
            byte[] bytes = encode(messages[i]);
            assertTrue(bytes.length >= 1);
            assertEquals(i, Byte.toUnsignedInt(bytes[0]), "wire type " + i);
        }
    }

    @Test
    void variableLengthMessagesUseBigEndianLengths() {
        byte[] text = encode(new Text("世界"));
        assertEquals(6, readU32(text, 1));

        byte[] clipboard = encode(new ClipboardSet(1, true, "abc"));
        assertEquals(3, readU32(clipboard, 10));

        byte[] scan = encode(new ScanFile("/a"));
        assertEquals(2, readU32(scan, 1));
    }

    @Test
    void utf8TextTruncationDoesNotSplitCodePoint() {
        String value = "a".repeat(299) + "世";
        byte[] bytes = encode(new Text(value));
        int length = readU32(bytes, 1);
        assertEquals(299, length);
        assertEquals(304, bytes.length);
        assertDoesNotThrow(() -> new String(bytes, 5, length, StandardCharsets.UTF_8));
    }

    private static int readU32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | (bytes[offset + 3] & 0xff);
    }
}
