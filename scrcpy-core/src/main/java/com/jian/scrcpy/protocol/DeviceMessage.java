package com.jian.scrcpy.protocol;

import java.util.Arrays;
import java.util.Objects;

public sealed interface DeviceMessage permits DeviceMessage.Clipboard, DeviceMessage.AckClipboard, DeviceMessage.UhidOutput {
    record Clipboard(String text) implements DeviceMessage {
        public Clipboard {
            text = Objects.requireNonNull(text, "text");
        }
    }

    record AckClipboard(long sequence) implements DeviceMessage {
    }

    record UhidOutput(int id, byte[] data) implements DeviceMessage {
        public UhidOutput {
            if (id < 0 || id > 0xffff) {
                throw new IllegalArgumentException("UHID id must fit uint16");
            }
            data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        }

        @Override
        public byte[] data() {
            return Arrays.copyOf(data, data.length);
        }
    }
}
