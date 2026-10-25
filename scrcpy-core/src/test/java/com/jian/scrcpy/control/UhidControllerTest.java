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
    void mouseUsesFiveByteRelativeReportsAndClampsMotion() {
        List<ControlMessages.Message> sent = new ArrayList<>();
        UhidMouse mouse = new UhidMouse(sent::add);

        mouse.buttons(1);
        mouse.move(200, -200);
        mouse.scroll(2, -3);
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
