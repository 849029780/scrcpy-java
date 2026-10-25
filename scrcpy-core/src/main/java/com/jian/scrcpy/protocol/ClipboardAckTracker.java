package com.jian.scrcpy.protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tracks SET_CLIPBOARD sequence numbers until scrcpy-server replies with
 * ACK_CLIPBOARD. Pending entries can optionally expire so a lost ACK cannot
 * leak memory forever.
 */
public final class ClipboardAckTracker {
    private static final ScheduledExecutorService EXPIRER =
            Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

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
        track(sequence, onAck, 0, null);
    }

    /**
     * Tracks an acknowledgement with an optional timeout.
     *
     * @param timeoutMillis zero disables expiration
     * @param onTimeout called when the pending entry expires; may be null
     */
    public void track(long sequence, Consumer<Long> onAck,
                      long timeoutMillis, Consumer<Long> onTimeout) {
        if (sequence == 0) {
            throw new IllegalArgumentException("clipboard sequence 0 is reserved for untracked writes");
        }
        if (onAck == null) {
            return;
        }
        pending.put(sequence, onAck);
        if (timeoutMillis > 0) {
            EXPIRER.schedule(() -> expire(sequence, onTimeout),
                    timeoutMillis, TimeUnit.MILLISECONDS);
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

    /**
     * Expires one sequence if it is still waiting for an acknowledgement.
     */
    public boolean expire(long sequence, Consumer<Long> onTimeout) {
        Consumer<Long> callback = pending.remove(sequence);
        if (callback == null) {
            return false;
        }
        if (onTimeout != null) {
            try {
                onTimeout.accept(sequence);
            } catch (Throwable ignored) {
                // A timeout observer must not terminate the shared scheduler.
            }
        }
        return true;
    }

    public int pendingCount() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "scrcpy-clipboard-ack-expirer");
            thread.setDaemon(true);
            return thread;
        }
    }
}
