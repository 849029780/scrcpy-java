package com.jian.scrcpy.app;

import javafx.scene.input.KeyCode;

/** Maps JavaFX physical keys to USB HID keyboard usages. */
final class HidKeyMapper {
    private HidKeyMapper() {}

    static int usage(KeyCode code) {
        if (code == null) return -1;
        String name = code.name();
        if (name.length() == 1 && name.charAt(0) >= 'A' && name.charAt(0) <= 'Z') return 0x04 + name.charAt(0) - 'A';
        if (name.startsWith("DIGIT") && name.length() == 6) {
            char d = name.charAt(5);
            if (d >= '1' && d <= '9') return 0x1e + d - '1';
            if (d == '0') return 0x27;
        }
        return switch (code) {
            case ENTER -> 0x28; case ESCAPE -> 0x29; case BACK_SPACE -> 0x2a; case TAB -> 0x2b; case SPACE -> 0x2c;
            case MINUS -> 0x2d; case EQUALS -> 0x2e; case OPEN_BRACKET -> 0x2f; case CLOSE_BRACKET -> 0x30;
            case BACK_SLASH -> 0x31; case SEMICOLON -> 0x33; case QUOTE -> 0x34; case BACK_QUOTE -> 0x35;
            case COMMA -> 0x36; case PERIOD -> 0x37; case SLASH -> 0x38; case CAPS -> 0x39;
            case F1 -> 0x3a; case F2 -> 0x3b; case F3 -> 0x3c; case F4 -> 0x3d; case F5 -> 0x3e; case F6 -> 0x3f;
            case F7 -> 0x40; case F8 -> 0x41; case F9 -> 0x42; case F10 -> 0x43; case F11 -> 0x44; case F12 -> 0x45;
            case PRINTSCREEN -> 0x46; case SCROLL_LOCK -> 0x47; case PAUSE -> 0x48; case INSERT -> 0x49;
            case HOME -> 0x4a; case PAGE_UP -> 0x4b; case DELETE -> 0x4c; case END -> 0x4d; case PAGE_DOWN -> 0x4e;
            case RIGHT -> 0x4f; case LEFT -> 0x50; case DOWN -> 0x51; case UP -> 0x52; case NUM_LOCK -> 0x53;
            case DIVIDE -> 0x54; case MULTIPLY -> 0x55; case SUBTRACT -> 0x56; case ADD -> 0x57;
            case NUMPAD0 -> 0x62; case NUMPAD1 -> 0x59; case NUMPAD2 -> 0x5a; case NUMPAD3 -> 0x5b;
            case NUMPAD4 -> 0x5c; case NUMPAD5 -> 0x5d; case NUMPAD6 -> 0x5e; case NUMPAD7 -> 0x5f;
            case NUMPAD8 -> 0x60; case NUMPAD9 -> 0x61; case DECIMAL -> 0x63;
            default -> -1;
        };
    }

    static int modifiers(boolean shift, boolean ctrl, boolean alt, boolean meta) {
        int value = 0;
        if (ctrl) value |= 0x01;
        if (shift) value |= 0x02;
        if (alt) value |= 0x04;
        if (meta) value |= 0x08;
        return value;
    }
}
