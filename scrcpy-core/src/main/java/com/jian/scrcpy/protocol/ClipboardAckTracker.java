package com.jian.scrcpy.protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tracks SET_CLIPBOARD sequence numbers until scrcpy-server replies with
 * ACK_CLIPBOARD. This is the protocol primitive needed for clipboard
 * autosync without confusing local and remote clipboard changes.
 */
public final class ClipboardAckTracker {
    private final AtomicLong nextSequence = new AtomicLong(1);
    private final Map<Long, Consumer<Long>> pending = new ConcurrentHashMap<>();

    public long nextSequence() {
        long sequence = nextSequence.getAndIncrement();
        if (sequence == 0) {
            sequence = nextSequence.getAndIncrement();
        }
        return sequence;
    }

    public void track(long sequence, Consumer<Long> onAck) {
        if (sequence == 0) {
            throw new IllegalArgumentException("clipboard sequence 0 is reserved for untracked writes");
        }
        if (onAck != null) {
            pending.put(sequence, onAck);
        }
    }

    public boolean acknowledge(long sequence) {
        Consumer<Long> callback = pending.remove(sequence);
        if (callback == null) {
            return false;
        }
        callback.accept(sequence);
        return true;
    }

    public int pendingCount() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
    }
}
