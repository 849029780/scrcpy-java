package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;

import java.util.function.Consumer;

/**
 * scrcpy 5.0.1 UHID relative mouse implementation.
 */
public final class UhidMouse implements AutoCloseable {
    public static final int HID_ID = 2;

    // Exact descriptor used by scrcpy 5.0.1 app/src/hid/hid_mouse.c.
    public static final byte[] REPORT_DESCRIPTOR = bytes(
            0x05,0x01,0x09,0x02,0xA1,0x01,0x09,0x01,0xA1,0x00,0x05,0x09,
            0x19,0x01,0x29,0x05,0x15,0x00,0x25,0x01,0x95,0x05,0x75,0x01,
            0x81,0x02,0x95,0x01,0x75,0x03,0x81,0x01,0x05,0x01,0x09,0x30,
            0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,
            0x81,0x06,0x05,0x0C,0x0A,0x38,0x02,0x15,0x81,0x25,0x7F,0x75,
            0x08,0x95,0x01,0x81,0x06,0xC0,0xC0);

    private final Consumer<ControlMessages.Message> sink;
    private boolean open;
    private int buttons;
    private double residualH;
    private double residualV;

    public UhidMouse(Consumer<ControlMessages.Message> sink) {
        this.sink = sink;
    }

    public void open() {
        if (open) return;
        sink.accept(new ControlMessages.UhidCreate(HID_ID, 0, 0, null, REPORT_DESCRIPTOR));
        open = true;
    }

    public void move(double dx, double dy) {
        open();
        int x = clampRelative(dx);
        int y = clampRelative(dy);
        if (x == 0 && y == 0) return;
        send(buttons, x, y, 0, 0);
    }

    public void buttons(int buttons) {
        open();
        this.buttons = buttons & 0x1f;
        send(this.buttons, 0, 0, 0, 0);
    }

    public void scroll(double horizontal, double vertical) {
        open();
        residualH += horizontal;
        residualV += vertical;
        int h = consumeScroll(true);
        int v = consumeScroll(false);
        if (h == 0 && v == 0) return;
        // scrcpy intentionally sends scroll with unknown/irrelevant buttons=0.
        send(0, 0, 0, v, h);
    }

    private int consumeScroll(boolean horizontal) {
        double value = horizontal ? residualH : residualV;
        double clamped = Math.max(-127, Math.min(127, value));
        int consumed = (int) clamped;
        if (horizontal) residualH = clamped - consumed;
        else residualV = clamped - consumed;
        return consumed;
    }

    private static int clampRelative(double value) {
        return (int) Math.max(-127, Math.min(127, Math.round(value)));
    }

    private void send(int buttons, int x, int y, int wheel, int pan) {
        sink.accept(new ControlMessages.UhidInput(HID_ID, new byte[] {
                (byte) buttons, (byte) x, (byte) y, (byte) wheel, (byte) pan
        }));
    }

    @Override
    public void close() {
        if (!open) return;
        buttons = 0;
        send(0, 0, 0, 0, 0);
        sink.accept(new ControlMessages.UhidDestroy(HID_ID));
        open = false;
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
