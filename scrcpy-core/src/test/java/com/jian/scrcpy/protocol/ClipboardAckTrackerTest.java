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

    @Test
    void expiresPendingAcknowledgement() throws InterruptedException {
        ClipboardAckTracker tracker = new ClipboardAckTracker();
        AtomicLong timedOut = new AtomicLong();

        long sequence = tracker.nextSequence();
        tracker.track(sequence, ignored -> fail("ack callback must not be called"),
                50, timedOut::set);

        long deadline = System.nanoTime() + 2_000_000_000L;
        while (tracker.pendingCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(0, tracker.pendingCount());
        assertEquals(sequence, timedOut.get());
        assertFalse(tracker.acknowledge(sequence));
    }

    @Test
    void acknowledgementWinsRaceWithExpiration() {
        ClipboardAckTracker tracker = new ClipboardAckTracker();
        AtomicLong acked = new AtomicLong();
        AtomicLong timedOut = new AtomicLong();

        long sequence = tracker.nextSequence();
        tracker.track(sequence, acked::set, 5_000, timedOut::set);

        assertTrue(tracker.acknowledge(sequence));
        assertEquals(sequence, acked.get());
        assertEquals(0, timedOut.get());
        assertEquals(0, tracker.pendingCount());
    }
}
