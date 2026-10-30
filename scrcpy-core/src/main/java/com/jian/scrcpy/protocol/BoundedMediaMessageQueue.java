package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;

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
        Iterator<MediaMessage> iterator = queue.iterator();
        while (iterator.hasNext()) {
            MediaMessage message = iterator.next();
            if (message instanceof MediaMessage.Packet packet
                    && !packet.config() && !packet.keyFrame()) {
                iterator.remove();
                return message;
            }
        }
        return null;
    }

    private static MediaMessage removeOldestMediaPacket(ArrayBlockingQueue<MediaMessage> queue) {
        Iterator<MediaMessage> iterator = queue.iterator();
        while (iterator.hasNext()) {
            MediaMessage message = iterator.next();
            if (message instanceof MediaMessage.Packet packet && !packet.config()) {
                iterator.remove();
                return message;
            }
        }
        return null;
    }

    private static MediaMessage removeDuplicateControl(ArrayBlockingQueue<MediaMessage> queue,
                                                       MediaMessage incoming) {
        Iterator<MediaMessage> iterator = queue.iterator();
        while (iterator.hasNext()) {
            MediaMessage queued = iterator.next();
            boolean duplicate = incoming instanceof MediaMessage.Codec
                    && queued instanceof MediaMessage.Codec
                    || incoming instanceof MediaMessage.Session
                    && queued instanceof MediaMessage.Session
                    || incoming instanceof MediaMessage.Packet incomingPacket
                    && incomingPacket.config()
                    && queued instanceof MediaMessage.Packet queuedPacket
                    && queuedPacket.config();
            if (duplicate) {
                iterator.remove();
                return queued;
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
