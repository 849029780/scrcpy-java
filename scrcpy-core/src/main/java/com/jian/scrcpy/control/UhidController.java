package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.DeviceMessage;

import java.util.Objects;
import java.util.function.Consumer;

/** Owns the UHID keyboard and mouse lifecycle for one scrcpy control channel. */
public final class UhidController implements AutoCloseable {
    private final UhidKeyboard keyboard;
    private final UhidMouse mouse;

    public UhidController(Consumer<ControlMessages.Message> sink) {
        Objects.requireNonNull(sink, "sink");
        keyboard = new UhidKeyboard(sink);
        mouse = new UhidMouse(sink);
    }

    public void openKeyboard() { keyboard.open(); }
    public void openMouse() { mouse.open(); }
    public UhidKeyboard keyboard() { return keyboard; }
    public UhidMouse mouse() { return mouse; }

    /** Release transient input state when the desktop window loses focus. */
    public void releaseInputState() {
        keyboard.releaseAll();
        mouse.releaseButtons();
    }

    public void handle(DeviceMessage message) {
        keyboard.handle(message);
    }

    @Override
    public void close() {
        Throwable first = null;
        try { mouse.close(); } catch (Throwable t) { first = t; }
        try { keyboard.close(); } catch (Throwable t) {
            if (first == null) first = t; else first.addSuppressed(t);
        }
        if (first != null) throw new IllegalStateException("Failed to close UHID devices", first);
    }
}
