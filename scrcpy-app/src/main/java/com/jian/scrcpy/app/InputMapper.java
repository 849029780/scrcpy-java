package com.jian.scrcpy.app;

import com.jian.scrcpy.protocol.ControlMessages;
import javafx.scene.input.KeyCode;

final class InputMapper {
    private InputMapper() {}

    static int androidKeyCode(KeyCode code) {
        if (code == null) return -1;
        if (code.isLetterKey()) return 29 + code.ordinal() - KeyCode.A.ordinal();
        if (code.isDigitKey()) {
            return switch (code) {
                case DIGIT0 -> 7; case DIGIT1 -> 8; case DIGIT2 -> 9; case DIGIT3 -> 10; case DIGIT4 -> 11;
                case DIGIT5 -> 12; case DIGIT6 -> 13; case DIGIT7 -> 14; case DIGIT8 -> 15; case DIGIT9 -> 16;
                default -> -1;
            };
        }
        return switch (code) {
            case HOME -> 3;
            case BACK_SPACE -> 67;
            case ENTER -> 66;
            case SPACE -> 62;
            case TAB -> 61;
            case ESCAPE -> 111;
            case UP -> 19;
            case DOWN -> 20;
            case LEFT -> 21;
            case RIGHT -> 22;
            case DELETE -> 112;
            case PAGE_UP -> 92;
            case PAGE_DOWN -> 93;
            case VOLUME_UP -> 24;
            case VOLUME_DOWN -> 25;
            default -> -1;
        };
    }

    static int metaState(boolean shift, boolean ctrl, boolean alt) {
        int state = 0;
        if (shift) state |= 0x1;   // AMETA_SHIFT_ON
        if (alt) state |= 0x2;     // AMETA_ALT_ON
        if (ctrl) state |= 0x1000; // AMETA_CTRL_ON
        return state;
    }

    static ControlMessages.Position position(VideoView view, double x, double y) {
        int width = view.frameWidth();
        int height = view.frameHeight();
        if (width <= 0 || height <= 0) return null;

        double boxW = view.getBoundsInLocal().getWidth();
        double boxH = view.getBoundsInLocal().getHeight();
        if (boxW <= 0 || boxH <= 0) return null;

        double scale = Math.min(boxW / width, boxH / height);
        double shownW = width * scale;
        double shownH = height * scale;
        double left = (boxW - shownW) / 2.0;
        double top = (boxH - shownH) / 2.0;
        if (x < left || y < top || x >= left + shownW || y >= top + shownH) return null;

        int px = Math.min(width - 1, Math.max(0, (int) ((x - left) / scale)));
        int py = Math.min(height - 1, Math.max(0, (int) ((y - top) / scale)));
        return new ControlMessages.Position(px, py, width, height);
    }
}
