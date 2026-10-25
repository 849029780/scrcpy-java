package com.jian.scrcpy.protocol;

import java.util.Arrays;

public final class ControlMessages {
    public static final long POINTER_ID_MOUSE = -1L;
    public static final long POINTER_ID_GENERIC_FINGER = -2L;
    public static final long POINTER_ID_VIRTUAL_FINGER = -3L;

    public static final int COPY_KEY_NONE = 0;
    public static final int COPY_KEY_COPY = 1;
    public static final int COPY_KEY_CUT = 2;

    private ControlMessages() {
    }

    public sealed interface Message permits Keycode, Text, Touch, Scroll, BackOrScreenOn, Simple,
            GetClipboard, ClipboardSet, DisplayPower, UhidCreate, UhidInput, UhidDestroy,
            StartApp, CameraSetTorch, ResizeDisplay, ScanFile {
    }

    public record Keycode(int action, int keycode, int repeat, int metaState) implements Message {
    }

    public record Text(String text) implements Message {
    }

    public record Position(int x, int y, int screenWidth, int screenHeight) {
        public Position {
            if (screenWidth < 0 || screenWidth > 0xffff || screenHeight < 0 || screenHeight > 0xffff) {
                throw new IllegalArgumentException("screen size must fit uint16");
            }
        }
    }

    public record Touch(int action, long pointerId, Position position, float pressure, int actionButton,
                        int buttons) implements Message {
    }

    public record Scroll(Position position, float hscroll, float vscroll, int buttons) implements Message {
    }

    public record BackOrScreenOn(int action) implements Message {
    }

    public record Simple(ControlMessageType type) implements Message {
        public Simple {
            if (type == null) throw new NullPointerException("type");
        }
    }

    public record GetClipboard(int copyKey) implements Message {
        public GetClipboard {
            if (copyKey < COPY_KEY_NONE || copyKey > COPY_KEY_CUT) {
                throw new IllegalArgumentException("invalid copy key: " + copyKey);
            }
        }
    }

    public record ClipboardSet(long sequence, boolean paste, String text) implements Message {
    }

    public record DisplayPower(boolean on) implements Message {
    }

    public record UhidCreate(int id, int vendorId, int productId, String name, byte[] reportDescriptor)
            implements Message {
        public UhidCreate {
            checkU16("id", id);
            checkU16("vendorId", vendorId);
            checkU16("productId", productId);
            reportDescriptor = copy(reportDescriptor);
            if (reportDescriptor.length > 0xffff) {
                throw new IllegalArgumentException("UHID report descriptor too large");
            }
        }

        @Override
        public byte[] reportDescriptor() {
            return copy(reportDescriptor);
        }
    }

    public record UhidInput(int id, byte[] data) implements Message {
        public UhidInput {
            checkU16("id", id);
            data = copy(data);
            if (data.length > 0xffff) throw new IllegalArgumentException("UHID input too large");
        }

        @Override
        public byte[] data() {
            return copy(data);
        }
    }

    public record UhidDestroy(int id) implements Message {
        public UhidDestroy {
            checkU16("id", id);
        }
    }

    public record StartApp(String name) implements Message {
    }

    public record CameraSetTorch(boolean on) implements Message {
    }

    public record ResizeDisplay(int width, int height) implements Message {
        public ResizeDisplay {
            checkU16("width", width);
            checkU16("height", height);
        }
    }

    public record ScanFile(String path) implements Message {
    }

    private static void checkU16(String name, int value) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException(name + " must fit uint16");
    }

    private static byte[] copy(byte[] data) {
        return data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }
}
