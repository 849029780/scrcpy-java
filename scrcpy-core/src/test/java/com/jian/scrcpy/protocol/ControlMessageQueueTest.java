package com.jian.scrcpy.protocol;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ControlMessageQueueTest {
    @Test
    void droppabilityMatchesScrcpy501() {
        assertFalse(ControlMessageQueue.isDroppable(new ControlMessages.UhidCreate(
                1, 1, 1, "kbd", new byte[]{1})));
        assertFalse(ControlMessageQueue.isDroppable(new ControlMessages.UhidDestroy(1)));
        assertTrue(ControlMessageQueue.isDroppable(new ControlMessages.UhidInput(1, new byte[]{1})));
        assertTrue(ControlMessageQueue.isDroppable(new ControlMessages.Keycode(0, 4, 0, 0)));
        assertTrue(ControlMessageQueue.isDroppable(new ControlMessages.ClipboardSet(1, false, "x")));
    }

    @Test
    void resizeMessagesAreCoalescedWhileSocketIsBackpressured() {
        ControlMessageQueue queue = new ControlMessageQueue();
        EmbeddedChannel channel = new EmbeddedChannel(new ControlMessageEncoder(), queue);
        channel.config().setWriteBufferWaterMark(new io.netty.channel.WriteBufferWaterMark(1, 2));

        assertTrue(queue.submit(new ControlMessages.ResizeDisplay(100, 200)));
        assertTrue(queue.submit(new ControlMessages.ResizeDisplay(300, 400)));

        // The queue API remains usable and never accumulates multiple pending resize messages.
        assertTrue(queue.queuedMessages() <= 1);
        channel.finishAndReleaseAll();
    }

    @Test
    void queueLimitMatchesUpstreamController() {
        assertEquals(60, ControlMessageQueue.QUEUE_LIMIT);
    }
}
