package com.jian.scrcpy.protocol;

public sealed interface DeviceMessage permits DeviceMessage.Clipboard, DeviceMessage.AckClipboard, DeviceMessage.UhidOutput {
    record Clipboard(String text) implements DeviceMessage {
    }

    record AckClipboard(long sequence) implements DeviceMessage {
    }

    record UhidOutput(int id, byte[] data) implements DeviceMessage {
    }
}