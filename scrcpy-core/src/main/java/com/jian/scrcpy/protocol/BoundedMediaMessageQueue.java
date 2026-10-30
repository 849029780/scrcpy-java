package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded live-media queue with explicit, serialized ByteBuf ownership.
 *
 * All queue mutations and removals happen under one lock. This is intentional:
 * evicting from an ArrayBlockingQueue while a separate consumer calls take()
 * makes ownership harder to reason about because the queue's weakly-consistent
 * iterators do not provide an atomic "remove this exact item and transfer its
 * ownership" operation to callers.
 */
public final class BoundedMediaMessageQueue {
    public enum OfferResult {
        ENQUEUED,
        ENQUEUED_AFTER_EVICTION,
        INCOMING_DROPPED
    }

    private final int capacity;
    private final ArrayDeque<MediaMessage> queue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    public BoundedMediaMessageQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    /**
     * Transfers ownership of incoming to this queue. If it cannot be queued,
     * its ByteBuf is released before this method returns.
     */
    public OfferResult offer(MediaMessage incoming) {
        lock.lock();
        try {
            if (queue.size() < capacity) {
                queue.addLast(incoming);
                notEmpty.signal();
                return OfferResult.ENQUEUED;
            }

            MediaMessage evicted = removeFirstMatching(message ->
                    message instanceof MediaMessage.Packet packet
                            && !packet.config() && !packet.keyFrame());
            if (evicted == null) {
                evicted = removeFirstMatching(message ->
                        message instanceof MediaMessage.Packet packet && !packet.config());
            }
            if (evicted == null && isControlOrConfig(incoming)) {
                evicted = removeFirstMatching(queued ->
                        incoming instanceof MediaMessage.Codec && queued instanceof MediaMessage.Codec
                                || incoming instanceof MediaMessage.Session && queued instanceof MediaMessage.Session
                                || incoming instanceof MediaMessage.Packet incomingPacket
                                && incomingPacket.config()
                                && queued instanceof MediaMessage.Packet queuedPacket
                                && queuedPacket.config());
                if (evicted == null) evicted = queue.pollFirst();
            }

            if (evicted != null) {
                release(evicted);
                queue.addLast(incoming);
                notEmpty.signal();
                return OfferResult.ENQUEUED_AFTER_EVICTION;
            }

            release(incoming);
            return OfferResult.INCOMING_DROPPED;
        } finally {
            lock.unlock();
        }
    }

    /** Waits until a message is available. Ownership transfers to the caller. */
    public MediaMessage take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) notEmpty.await();
            return queue.removeFirst();
        } finally {
            lock.unlock();
        }
    }

    /** Returns the next message or null. Ownership transfers to the caller. */
    public MediaMessage poll() {
        lock.lock();
        try {
            return queue.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private MediaMessage removeFirstMatching(java.util.function.Predicate<MediaMessage> predicate) {
        for (Iterator<MediaMessage> iterator = queue.iterator(); iterator.hasNext();) {
            MediaMessage message = iterator.next();
            if (predicate.test(message)) {
                iterator.remove();
                return message;
            }
        }
        return null;
    }

    private static boolean isControlOrConfig(MediaMessage message) {
        return !(message instanceof MediaMessage.Packet packet) || packet.config();
    }

    private static void release(MediaMessage message) {
        if (message instanceof MediaMessage.Packet packet) {
            ByteBuf data = packet.data();
            ReferenceCountUtil.release(data);
        }
    }
}
