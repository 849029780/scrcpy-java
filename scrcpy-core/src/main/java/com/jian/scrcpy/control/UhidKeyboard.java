package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.DeviceMessage;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * scrcpy 5.0.1 UHID keyboard implementation (USB HID boot-keyboard report).
 * Frontends provide USB HID usages, not Android keycodes.
 */
public final class UhidKeyboard implements AutoCloseable {
    public static final int HID_ID = 1;
    private static final int MAX_KEYS = 6;

    // Exact descriptor used by scrcpy 5.0.1 app/src/hid/hid_keyboard.c.
    public static final byte[] REPORT_DESCRIPTOR = bytes(
            0x05,0x01,0x09,0x06,0xA1,0x01,0x05,0x07,0x19,0xE0,0x29,0xE7,
            0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,0x75,0x08,
            0x95,0x01,0x81,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x75,0x01,
            0x95,0x05,0x91,0x02,0x75,0x03,0x95,0x01,0x91,0x01,0x05,0x07,
            0x19,0x00,0x29,0x65,0x15,0x00,0x25,0x65,0x75,0x08,0x95,0x06,
            0x81,0x00,0xC0);

    private final Consumer<ControlMessages.Message> sink;
    private final Set<Integer> pressed = new LinkedHashSet<>();
    private int modifiers;
    private int leds;
    private boolean open;

    public UhidKeyboard(Consumer<ControlMessages.Message> sink) {
        this.sink = sink;
    }

    public void open() {
        if (open) return;
        sink.accept(new ControlMessages.UhidCreate(HID_ID, 0, 0, null, REPORT_DESCRIPTOR));
        open = true;
    }

    public void key(int usage, boolean down, int modifiers) {
        if (usage < 0 || usage > 0x65) throw new IllegalArgumentException("unsupported HID keyboard usage: " + usage);
        open();
        this.modifiers = modifiers & 0xff;
        if (down) pressed.add(usage); else pressed.remove(usage);
        sendReport();
    }

    /** Release every pressed key and modifier without destroying the UHID device. */
    public void releaseAll() {
        if (!open) return;
        pressed.clear();
        modifiers = 0;
        sendReport();
    }

    public void modifiers(int modifiers) {
        open();
        this.modifiers = modifiers & 0xff;
        sendReport();
    }

    public int leds() {
        return leds;
    }

    public boolean capsLockLed() {
        return (leds & 0x02) != 0;
    }

    public boolean numLockLed() {
        return (leds & 0x01) != 0;
    }

    public void handle(DeviceMessage message) {
        if (message instanceof DeviceMessage.UhidOutput output && output.id() == HID_ID) {
            byte[] data = output.data();
            if (data.length > 0) leds = data[0] & 0xff;
        }
    }

    private void sendReport() {
        byte[] report = new byte[8];
        report[0] = (byte) modifiers;
        if (pressed.size() > MAX_KEYS) {
            Arrays.fill(report, 2, 8, (byte) 0x01);
        } else {
            int i = 2;
            for (int usage : pressed) report[i++] = (byte) usage;
        }
        sink.accept(new ControlMessages.UhidInput(HID_ID, report));
    }

    @Override
    public void close() {
        if (!open) return;
        try {
            releaseAll();
        } finally {
            try {
                sink.accept(new ControlMessages.UhidDestroy(HID_ID));
            } finally {
                open = false;
            }
        }
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
