package com.jian.scrcpy.app;

import com.jian.scrcpy.control.UhidMouse;
import javafx.scene.input.MouseButton;

final class MouseInputMapper {
    private MouseInputMapper() {}

    static int uhidButton(MouseButton button) {
        if (button == null) return 0;
        return switch (button) {
            case PRIMARY -> UhidMouse.BUTTON_LEFT;
            case SECONDARY -> UhidMouse.BUTTON_RIGHT;
            case MIDDLE -> UhidMouse.BUTTON_MIDDLE;
            case BACK -> UhidMouse.BUTTON_BACK;
            case FORWARD -> UhidMouse.BUTTON_FORWARD;
            default -> 0;
        };
    }
}
