package com.jian.scrcpy.app;

import com.jian.scrcpy.control.UhidMouse;
import javafx.scene.input.MouseButton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MouseInputMapperTest {
    @Test
    void mapsAllStandardButtons() {
        assertEquals(UhidMouse.BUTTON_LEFT, MouseInputMapper.uhidButton(MouseButton.PRIMARY));
        assertEquals(UhidMouse.BUTTON_RIGHT, MouseInputMapper.uhidButton(MouseButton.SECONDARY));
        assertEquals(UhidMouse.BUTTON_MIDDLE, MouseInputMapper.uhidButton(MouseButton.MIDDLE));
        assertEquals(UhidMouse.BUTTON_BACK, MouseInputMapper.uhidButton(MouseButton.BACK));
        assertEquals(UhidMouse.BUTTON_FORWARD, MouseInputMapper.uhidButton(MouseButton.FORWARD));
    }
}
