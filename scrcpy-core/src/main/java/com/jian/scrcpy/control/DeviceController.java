package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.ControlMessageType;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * UI-agnostic device control facade.
 *
 * Desktop JavaFX, WebSocket/Web and future frontends translate their native
 * input events into these normalized operations. Only this layer creates
 * scrcpy protocol messages.
 */
public final class DeviceController {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_HOVER_MOVE = 7;
    public static final long POINTER_ID_MOUSE = -1L;

    private final Consumer<ControlMessages.Message> sink;

    public DeviceController(Consumer<ControlMessages.Message> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public void key(int action, int androidKeyCode, int repeat, int metaState) {
        sink.accept(new ControlMessages.Keycode(action, androidKeyCode, repeat, metaState));
    }

    public void text(String text) {
        if (text != null && !text.isEmpty()) sink.accept(new ControlMessages.Text(text));
    }

    /**
     * Unicode-safe text entry through Android clipboard + paste.
     * scrcpy SDK INJECT_TEXT cannot reliably inject CJK characters.
     */
    public void pasteText(String text) {
        if (text != null && !text.isEmpty()) {
            sink.accept(new ControlMessages.ClipboardSet(0, true, text));
        }
    }

    public void touch(int action, long pointerId, ScreenPosition position,
                      float pressure, int actionButton, int buttons) {
        sink.accept(new ControlMessages.Touch(action, pointerId, position.toProtocol(),
                pressure, actionButton, buttons));
    }

    public void hover(ScreenPosition position) {
        touch(ACTION_HOVER_MOVE, POINTER_ID_MOUSE, position, 0f, 0, 0);
    }

    public void scroll(ScreenPosition position, float horizontal, float vertical, int buttons) {
        sink.accept(new ControlMessages.Scroll(position.toProtocol(), horizontal, vertical, buttons));
    }

    public void backOrScreenOn(int action) {
        sink.accept(new ControlMessages.BackOrScreenOn(action));
    }

    public void rotateDevice() {
        sink.accept(new ControlMessages.Simple(ControlMessageType.ROTATE_DEVICE));
    }

    public void setDisplayPower(boolean on) {
        sink.accept(new ControlMessages.DisplayPower(on));
    }

    public record ScreenPosition(int x, int y, int screenWidth, int screenHeight) {
        public ScreenPosition {
            if (screenWidth <= 0 || screenHeight <= 0) throw new IllegalArgumentException("invalid screen size");
            if (x < 0 || y < 0 || x >= screenWidth || y >= screenHeight) {
                throw new IllegalArgumentException("position outside screen");
            }
        }

        ControlMessages.Position toProtocol() {
            return new ControlMessages.Position(x, y, screenWidth, screenHeight);
        }
    }
}
