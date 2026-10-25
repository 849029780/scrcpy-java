package com.jian.scrcpy.protocol;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class ClipboardAckTrackerTest {
    @Test
    void acknowledgesTrackedSequenceExactlyOnce() {
        ClipboardAckTracker tracker = new ClipboardAckTracker();
        AtomicLong acked = new AtomicLong();

        long sequence = tracker.nextSequence();
        tracker.track(sequence, acked::set);

        assertEquals(1, tracker.pendingCount());
        assertTrue(tracker.acknowledge(sequence));
        assertEquals(sequence, acked.get());
        assertEquals(0, tracker.pendingCount());
        assertFalse(tracker.acknowledge(sequence));
    }

    @Test
    void clearDropsPendingAcknowledgements() {
        ClipboardAckTracker tracker = new ClipboardAckTracker();
        tracker.track(tracker.nextSequence(), ignored -> fail("must not be called"));
        tracker.clear();
        assertEquals(0, tracker.pendingCount());
    }
}
