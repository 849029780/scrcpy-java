package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;

/**
 * Bounded live-media queue that sheds stale media packets before control or
 * codec-configuration messages. Queue ownership includes the packet ByteBuf.
 */
public final class BoundedMediaMessageQueue {
    public enum OfferResult {
        ENQUEUED,
        ENQUEUED_AFTER_EVICTION,
        INCOMING_DROPPED
    }

    private BoundedMediaMessageQueue() {
    }

    public static OfferResult offer(ArrayBlockingQueue<MediaMessage> queue, MediaMessage incoming) {
        if (queue.offer(incoming)) {
            return OfferResult.ENQUEUED;
        }

        MediaMessage evicted = removeOldestNonKeyFramePacket(queue);
        if (evicted == null) {
            evicted = removeOldestMediaPacket(queue);
        }
        if (evicted == null && isControlOrConfig(incoming)) {
            evicted = removeDuplicateControl(queue, incoming);
            if (evicted == null) {
                // Control/configuration messages are rare and must not be
                // starved by a queue full of other control messages.
                evicted = queue.poll();
            }
        }

        if (evicted != null) {
            release(evicted);
            if (queue.offer(incoming)) {
                return OfferResult.ENQUEUED_AFTER_EVICTION;
            }
        }

        release(incoming);
        return OfferResult.INCOMING_DROPPED;
    }

    private static MediaMessage removeOldestNonKeyFramePacket(ArrayBlockingQueue<MediaMessage> queue) {
        return removeFirstMatching(queue, message ->
                message instanceof MediaMessage.Packet packet
                        && !packet.config() && !packet.keyFrame());
    }

    private static MediaMessage removeOldestMediaPacket(ArrayBlockingQueue<MediaMessage> queue) {
        return removeFirstMatching(queue, message ->
                message instanceof MediaMessage.Packet packet && !packet.config());
    }

    private static MediaMessage removeDuplicateControl(ArrayBlockingQueue<MediaMessage> queue,
                                                       MediaMessage incoming) {
        return removeFirstMatching(queue, queued ->
                incoming instanceof MediaMessage.Codec && queued instanceof MediaMessage.Codec
                        || incoming instanceof MediaMessage.Session && queued instanceof MediaMessage.Session
                        || incoming instanceof MediaMessage.Packet incomingPacket
                        && incomingPacket.config()
                        && queued instanceof MediaMessage.Packet queuedPacket
                        && queuedPacket.config());
    }

    /**
     * ArrayBlockingQueue's iterator is weakly consistent. Calling iterator.remove()
     * while the decoder thread concurrently polls can remove a different element
     * after the queue shifts, causing a buffer to be released while it is still
     * queued and later released again by the consumer. Find a candidate, then
     * remove that exact object atomically under ArrayBlockingQueue's own lock.
     */
    private static MediaMessage removeFirstMatching(ArrayBlockingQueue<MediaMessage> queue,
                                                    Predicate<MediaMessage> predicate) {
        for (;;) {
            MediaMessage candidate = null;
            for (MediaMessage message : queue) {
                if (predicate.test(message)) {
                    candidate = message;
                    break;
                }
            }
            if (candidate == null) {
                return null;
            }
            MediaMessage selected = candidate;
            if (queue.removeIf(message -> message == selected)) {
                return selected;
            }
            // The decoder may have polled this exact candidate after iteration.
            // Retry against the current queue; never release an item we didn't remove.
        }
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
