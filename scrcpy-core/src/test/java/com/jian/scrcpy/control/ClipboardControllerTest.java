package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.DeviceMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ClipboardControllerTest {
    @Test
    void synchronizesBothDirectionsWithoutPaste() {
        List<ControlMessages.Message> sent = new ArrayList<>();
        ClipboardController controller = new ClipboardController(sent::add);
        AtomicReference<String> received = new AtomicReference<>();
        controller.onRemoteClipboard(received::set);

        controller.setRemoteClipboard("PC中文");
        ControlMessages.ClipboardSet set =
                assertInstanceOf(ControlMessages.ClipboardSet.class, sent.get(0));
        assertEquals(0, set.sequence());
        assertFalse(set.paste());
        assertEquals("PC中文", set.text());

        controller.handle(new DeviceMessage.Clipboard("手机复制"));
        assertEquals("手机复制", received.get());

        controller.requestRemoteClipboard();
        ControlMessages.GetClipboard get =
                assertInstanceOf(ControlMessages.GetClipboard.class, sent.get(1));
        assertEquals(0, get.copyKey());
    }
}
