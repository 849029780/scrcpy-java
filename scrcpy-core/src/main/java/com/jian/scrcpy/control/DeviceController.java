package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;

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

    public void touch(int action, long pointerId, ScreenPosition position,
                      float pressure, int actionButton, int buttons) {
        sink.accept(new ControlMessages.Touch(action, pointerId, position.toProtocol(),
                pressure, actionButton, buttons));
    }

    public void scroll(ScreenPosition position, float horizontal, float vertical, int buttons) {
        sink.accept(new ControlMessages.Scroll(position.toProtocol(), horizontal, vertical, buttons));
    }

    public void backOrScreenOn(int action) {
        sink.accept(new ControlMessages.BackOrScreenOn(action));
    }

    public void rotateDevice() {
        sink.accept(new ControlMessages.Simple(ControlMessages.ControlMessageType.ROTATE_DEVICE));
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
