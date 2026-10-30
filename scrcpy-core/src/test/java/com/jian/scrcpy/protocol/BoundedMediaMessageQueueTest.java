package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BoundedMediaMessageQueueTest {

    @Test
    void overflowDropsWholeReferenceChainAndWaitsForNextKeyFrame() {
        BoundedMediaMessageQueue queue = new BoundedMediaMessageQueue(2);
        ByteBuf firstData = packetBuffer();
        ByteBuf secondData = packetBuffer();
        ByteBuf dependentData = packetBuffer();
        ByteBuf keyFrameData = packetBuffer();

        MediaMessage.Packet first = new MediaMessage.Packet(1, false, true, firstData);
        MediaMessage.Packet second = new MediaMessage.Packet(2, false, false, secondData);
        queue.offer(first);
        queue.offer(second);

        assertEquals(BoundedMediaMessageQueue.OfferResult.INCOMING_DROPPED,
                queue.offer(new MediaMessage.Packet(3, false, false, overflowData)));
        assertEquals(0, firstData.refCnt());
        assertEquals(0, secondData.refCnt());
        assertTrue(queue.isEmpty());

        assertEquals(BoundedMediaMessageQueue.OfferResult.INCOMING_DROPPED,
                queue.offer(new MediaMessage.Packet(4, false, false, dependentData)));
        assertEquals(0, dependentData.refCnt());

        MediaMessage.Packet recoveryKeyFrame = new MediaMessage.Packet(5, false, true, keyFrameData);
        assertEquals(BoundedMediaMessageQueue.OfferResult.ENQUEUED, queue.offer(recoveryKeyFrame));
        assertSame(recoveryKeyFrame, queue.poll());
        keyFrameData.release();
    }

    @Test
    void preservesCodecSessionAndConfigurationDuringKeyFrameRecovery() {
        BoundedMediaMessageQueue queue = new BoundedMediaMessageQueue(4);
        MediaMessage.Codec codec = new MediaMessage.Codec(CodecId.H264);
        MediaMessage.Session session = new MediaMessage.Session(1080, 1920, false);
        ByteBuf configData = packetBuffer();
        ByteBuf staleData = packetBuffer();
        ByteBuf overflowData = packetBuffer();

        queue.offer(codec);
        queue.offer(session);
        queue.offer(new MediaMessage.Packet(0, true, false, configData));
        queue.offer(new MediaMessage.Packet(1, false, false, staleData));
        // Queue is full; overload flushes the queued inter frame and rejects
        // the incoming inter frame while retaining stream metadata/config.
        assertEquals(BoundedMediaMessageQueue.OfferResult.INCOMING_DROPPED,
                queue.offer(new MediaMessage.Packet(2, false, false, packetBuffer())));
        assertEquals(0, staleData.refCnt());
        assertEquals(0, overflowData.refCnt());
        assertEquals(1, configData.refCnt());
        assertSame(codec, queue.poll());
        assertSame(session, queue.poll());
        MediaMessage.Packet config = (MediaMessage.Packet) queue.poll();
        assertTrue(config.config());
        configData.release();
        assertEquals(0, overflowData.refCnt());
    }

    @Test
    void doesNotEvictKeyFrameAndContinueWithDependentFrames() {
        BoundedMediaMessageQueue queue = new BoundedMediaMessageQueue(2);
        ByteBuf keyData = packetBuffer();
        ByteBuf interData = packetBuffer();
        ByteBuf incomingData = packetBuffer();
        MediaMessage.Packet key = new MediaMessage.Packet(1, false, true, keyData);
        MediaMessage.Packet inter = new MediaMessage.Packet(2, false, false, interData);
        queue.offer(key);
        queue.offer(inter);

        assertEquals(BoundedMediaMessageQueue.OfferResult.INCOMING_DROPPED,
                queue.offer(new MediaMessage.Packet(3, false, false, incomingData)));
        assertEquals(0, keyData.refCnt());
        assertEquals(0, interData.refCnt());
        assertEquals(0, incomingData.refCnt());
        assertTrue(queue.isEmpty());
    }

    @Test
    void dropsIncomingMediaWhenQueueContainsOnlyControlMessages() {
        BoundedMediaMessageQueue queue = new BoundedMediaMessageQueue(2);
        MediaMessage.Codec codec = new MediaMessage.Codec(CodecId.H264);
        MediaMessage.Session session = new MediaMessage.Session(1080, 1920, false);
        ByteBuf incomingData = packetBuffer();
        queue.offer(codec);
        queue.offer(session);

        var result = queue.offer(new MediaMessage.Packet(3, false, false, incomingData));

        assertEquals(BoundedMediaMessageQueue.OfferResult.INCOMING_DROPPED, result);
        assertEquals(0, incomingData.refCnt());
        assertSame(codec, queue.poll());
        assertSame(session, queue.poll());
    }

    @Test
    void concurrentConsumerAndEvictionReleaseEveryBufferExactlyOnce() throws Exception {
        BoundedMediaMessageQueue queue = new BoundedMediaMessageQueue(8);
        List<ByteBuf> buffers = new ArrayList<>();
        AtomicBoolean producerDone = new AtomicBoolean();
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                while (!producerDone.get() || !queue.isEmpty()) {
                    MediaMessage message = queue.poll();
                    if (message == null) {
                        Thread.yield();
                        continue;
                    }
                    if (message instanceof MediaMessage.Packet packet) {
                        packet.data().release();
                    }
                }
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            }
        });
        consumer.start();

        for (int i = 0; i < 20_000; i++) {
            ByteBuf data = packetBuffer();
            buffers.add(data);
            queue.offer(new MediaMessage.Packet(i, false, i % 120 == 0, data));
        }
        producerDone.set(true);
        consumer.join(5_000);

        assertFalse(consumer.isAlive(), "consumer should drain the queue");
        assertNull(consumerFailure.get(), "consumer must not double-release a buffer");
        for (ByteBuf data : buffers) {
            assertEquals(0, data.refCnt(), "every accepted, evicted or dropped buffer must be released");
        }
    }

    private static ByteBuf packetBuffer() {
        return Unpooled.buffer(1).writeByte(1);
    }
}
