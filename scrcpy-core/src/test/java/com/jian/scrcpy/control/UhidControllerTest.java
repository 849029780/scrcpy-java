package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.DeviceMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class UhidControllerTest {
    @Test
    void keyboardCreatesReportsTracksKeysAndDestroys() {
        List<ControlMessages.Message> sent = new ArrayList<>();
        UhidKeyboard keyboard = new UhidKeyboard(sent::add);

        keyboard.key(0x04, true, 0x02); // Shift+A
        keyboard.key(0x04, false, 0);
        keyboard.handle(new DeviceMessage.UhidOutput(UhidKeyboard.HID_ID, new byte[]{0x03}));
        assertTrue(keyboard.capsLockLed());
        assertTrue(keyboard.numLockLed());
        keyboard.close();

        ControlMessages.UhidCreate create = assertInstanceOf(ControlMessages.UhidCreate.class, sent.get(0));
        assertEquals(1, create.id());
        assertArrayEquals(UhidKeyboard.REPORT_DESCRIPTOR, create.reportDescriptor());
        assertArrayEquals(new byte[]{0x02,0,0x04,0,0,0,0,0},
                assertInstanceOf(ControlMessages.UhidInput.class, sent.get(1)).data());
        assertArrayEquals(new byte[8], assertInstanceOf(ControlMessages.UhidInput.class, sent.get(2)).data());
        assertInstanceOf(ControlMessages.UhidDestroy.class, sent.get(sent.size() - 1));
    }

    @Test
    void controllerReleasesTransientKeyboardAndMouseState() {
        List<ControlMessages.Message> sent = new ArrayList<>();
        UhidController controller = new UhidController(sent::add);

        controller.keyboard().key(0x04, true, 0x02);
        controller.mouse().button(UhidMouse.BUTTON_LEFT, true);
        int before = sent.size();

        controller.releaseInputState();

        assertTrue(sent.size() >= before + 2);
        ControlMessages.UhidInput keyboardRelease = assertInstanceOf(ControlMessages.UhidInput.class, sent.get(before));
        assertArrayEquals(new byte[8], keyboardRelease.data());
        ControlMessages.UhidInput mouseRelease = assertInstanceOf(ControlMessages.UhidInput.class, sent.get(before + 1));
        assertArrayEquals(new byte[]{0,0,0,0,0}, mouseRelease.data());
        controller.close();
    }

    @Test
    void keyboardCloseStillDestroysWhenReleaseSendFails() {
        UhidKeyboard keyboard = new UhidKeyboard(message -> {
            if (message instanceof ControlMessages.UhidInput) throw new IllegalStateException("send failed");
        });
        keyboard.open();
        assertThrows(IllegalStateException.class, keyboard::close);
        assertDoesNotThrow(keyboard::close);
    }

    @Test
    void mouseCloseStillDestroysWhenReleaseSendFails() {
        UhidMouse mouse = new UhidMouse(message -> {
            if (message instanceof ControlMessages.UhidInput) throw new IllegalStateException("send failed");
        });
        mouse.open();
        assertThrows(IllegalStateException.class, mouse::close);
        assertDoesNotThrow(mouse::close);
    }

    @Test
    void mouseUsesFiveByteRelativeReportsAndClampsMotion() {
        List<ControlMessages.Message> sent = new ArrayList<>();
        UhidMouse mouse = new UhidMouse(sent::add);

        mouse.button(UhidMouse.BUTTON_LEFT, true);
        assertEquals(UhidMouse.BUTTON_LEFT, mouse.buttons());
        mouse.move(200, -200);
        mouse.scroll(2, -3);
        mouse.button(UhidMouse.BUTTON_LEFT, false);
        assertEquals(0, mouse.buttons());
        mouse.close();

        assertEquals(2, assertInstanceOf(ControlMessages.UhidCreate.class, sent.get(0)).id());
        assertArrayEquals(new byte[]{1,0,0,0,0},
                assertInstanceOf(ControlMessages.UhidInput.class, sent.get(1)).data());
        assertArrayEquals(new byte[]{1,127,-127,0,0},
                assertInstanceOf(ControlMessages.UhidInput.class, sent.get(2)).data());
        assertArrayEquals(new byte[]{0,0,0,-3,2},
                assertInstanceOf(ControlMessages.UhidInput.class, sent.get(3)).data());
        assertInstanceOf(ControlMessages.UhidDestroy.class, sent.get(sent.size() - 1));
    }
}
