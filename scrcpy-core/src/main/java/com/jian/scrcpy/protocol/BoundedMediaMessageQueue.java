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
 * If overload forces a compressed video packet to be dropped, the queue enters
 * key-frame recovery mode. Inter frames are discarded until the next key frame
 * so the decoder never consumes frames whose reference chain was broken.
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
    private boolean awaitingKeyFrame;

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
            if (incoming instanceof MediaMessage.Packet packet && !packet.config()) {
                if (awaitingKeyFrame && !packet.keyFrame()) {
                    release(incoming);
                    return OfferResult.INCOMING_DROPPED;
                }
                if (queue.size() >= capacity) {
                    // Dropping any compressed frame breaks the predictive
                    // reference chain, not just dropping a non-key frame.
                    enterKeyFrameRecovery();
                    if (!packet.keyFrame() || queue.size() >= capacity) {
                        release(incoming);
                        return OfferResult.INCOMING_DROPPED;
                    }
                    awaitingKeyFrame = false;
                    queue.addLast(incoming);
                    notEmpty.signal();
                    return OfferResult.ENQUEUED_AFTER_EVICTION;
                }
                if (packet.keyFrame() && awaitingKeyFrame) {
                    awaitingKeyFrame = false;
                }
                queue.addLast(incoming);
                notEmpty.signal();
                return OfferResult.ENQUEUED;
            }

            if (queue.size() < capacity) {
                queue.addLast(incoming);
                notEmpty.signal();
                return OfferResult.ENQUEUED;
            }

            // Control/session metadata must not evict an arbitrary compressed
            // frame without also invalidating the queued reference chain.
            int removed = enterKeyFrameRecovery();
            if (queue.size() >= capacity && isControlOrConfig(incoming)) {
                MediaMessage duplicate = removeFirstMatching(queued ->
                        incoming instanceof MediaMessage.Codec && queued instanceof MediaMessage.Codec
                                || incoming instanceof MediaMessage.Session && queued instanceof MediaMessage.Session
                                || incoming instanceof MediaMessage.Packet incomingPacket
                                && incomingPacket.config()
                                && queued instanceof MediaMessage.Packet queuedPacket
                                && queuedPacket.config());
                if (duplicate != null) {
                    release(duplicate);
                    removed++;
                }
            }

            if (queue.size() < capacity) {
                queue.addLast(incoming);
                notEmpty.signal();
                return removed > 0
                        ? OfferResult.ENQUEUED_AFTER_EVICTION
                        : OfferResult.ENQUEUED;
            }

            release(incoming);
            return OfferResult.INCOMING_DROPPED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes queued compressed frames and waits for a clean key frame.
     * Codec/session messages and codec-configuration packets are retained.
     */
    private int enterKeyFrameRecovery() {
        awaitingKeyFrame = true;
        int removed = 0;
        for (Iterator<MediaMessage> iterator = queue.iterator(); iterator.hasNext();) {
            MediaMessage message = iterator.next();
            if (message instanceof MediaMessage.Packet packet && !packet.config()) {
                iterator.remove();
                release(message);
                removed++;
            }
        }
        return removed;
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

    /** Returns the current number of queued messages. */
    public int size() {
        lock.lock();
        try {
            return queue.size();
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
