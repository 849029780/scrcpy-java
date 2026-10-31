package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded queue for independent audio packets.
 *
 * Unlike H.264/H.265 video, OPUS/AAC packets do not depend on a preceding
 * key-frame reference chain. On overload, evict the oldest media packet and
 * retain the newest packet to keep playback latency bounded.
 *
 * Ownership of Packet.data() transfers to this queue on offer(), and transfers
 * to the consumer on poll()/take(). Evicted or rejected buffers are released.
 */
public final class AudioMediaMessageQueue {
    public enum OfferResult {
        ENQUEUED,
        ENQUEUED_AFTER_EVICTION,
        INCOMING_DROPPED
    }

    private final int capacity;
    private final ArrayDeque<MediaMessage> queue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    public AudioMediaMessageQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    public OfferResult offer(MediaMessage incoming) {
        lock.lock();
        try {
            if (queue.size() >= capacity) {
                MediaMessage evicted = removeOldestMediaPacket();
                if (evicted != null) {
                    release(evicted);
                } else {
                    // Preserve codec/session/config metadata. If the queue is
                    // entirely metadata, there is no safe media packet to evict.
                    release(incoming);
                    return OfferResult.INCOMING_DROPPED;
                }
                if (queue.size() >= capacity) {
                    release(incoming);
                    return OfferResult.INCOMING_DROPPED;
                }
                queue.addLast(incoming);
                notEmpty.signal();
                return OfferResult.ENQUEUED_AFTER_EVICTION;
            }

            queue.addLast(incoming);
            notEmpty.signal();
            return OfferResult.ENQUEUED;
        } finally {
            lock.unlock();
        }
    }

    private MediaMessage removeOldestMediaPacket() {
        for (Iterator<MediaMessage> it = queue.iterator(); it.hasNext();) {
            MediaMessage message = it.next();
            if (message instanceof MediaMessage.Packet packet && !packet.config()) {
                it.remove();
                return message;
            }
        }
        return null;
    }

    public MediaMessage take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) notEmpty.await();
            return queue.removeFirst();
        } finally {
            lock.unlock();
        }
    }

    public MediaMessage poll() {
        lock.lock();
        try {
            return queue.pollFirst();
        } finally {
            lock.unlock();
        }
    }

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

    private static void release(MediaMessage message) {
        if (message instanceof MediaMessage.Packet packet) {
            ReferenceCountUtil.release(packet.data());
        }
    }
}
