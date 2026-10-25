package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.ControlMessageType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DeviceControllerTest {
    @Test
    void frontendOperationsProduceProtocolMessagesWithoutUiDependency() {
        List<ControlMessages.Message> sent = new ArrayList<>();
        DeviceController controller = new DeviceController(sent::add);
        DeviceController.ScreenPosition p = new DeviceController.ScreenPosition(10, 20, 1080, 1920);

        controller.key(DeviceController.ACTION_DOWN, 29, 0, 0);
        controller.text("a");
        controller.pasteText("中文");
        controller.touch(DeviceController.ACTION_DOWN, -1L, p, 1f, 1, 1);
        controller.scroll(p, 1f, -1f, 0);
        controller.hover(p);
        controller.rotateDevice();
        controller.setDisplayPower(false);

        assertEquals(8, sent.size());
        assertInstanceOf(ControlMessages.Keycode.class, sent.get(0));
        assertInstanceOf(ControlMessages.Text.class, sent.get(1));
        ControlMessages.ClipboardSet unicode = assertInstanceOf(ControlMessages.ClipboardSet.class, sent.get(2));
        assertEquals("中文", unicode.text());
        assertTrue(unicode.paste());
        ControlMessages.Touch touch = assertInstanceOf(ControlMessages.Touch.class, sent.get(3));
        assertEquals(10, touch.position().x());
        assertEquals(20, touch.position().y());
        assertInstanceOf(ControlMessages.Scroll.class, sent.get(4));
        ControlMessages.Touch hover = assertInstanceOf(ControlMessages.Touch.class, sent.get(5));
        assertEquals(DeviceController.ACTION_HOVER_MOVE, hover.action());
        assertEquals(DeviceController.POINTER_ID_MOUSE, hover.pointerId());
        assertEquals(0, hover.buttons());
        assertEquals(ControlMessageType.ROTATE_DEVICE,
                assertInstanceOf(ControlMessages.Simple.class, sent.get(6)).type());
        assertFalse(assertInstanceOf(ControlMessages.DisplayPower.class, sent.get(7)).on());
    }

    @Test
    void screenPositionRejectsCoordinatesOutsideFrame() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceController.ScreenPosition(1080, 0, 1080, 1920));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceController.ScreenPosition(0, -1, 1080, 1920));
    }
}
