package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class BoundedMediaMessageQueueTest {

    @Test
    void evictsOldestNonKeyFrameAndReleasesItsBuffer() {
        ArrayBlockingQueue<MediaMessage> queue = new ArrayBlockingQueue<>(2);
        ByteBuf staleData = packetBuffer();
        ByteBuf keyFrameData = packetBuffer();
        ByteBuf incomingData = packetBuffer();
        MediaMessage.Packet stale = new MediaMessage.Packet(1, false, false, staleData);
        MediaMessage.Packet keyFrame = new MediaMessage.Packet(2, false, true, keyFrameData);
        MediaMessage.Packet incoming = new MediaMessage.Packet(3, false, false, incomingData);
        queue.add(stale);
        queue.add(keyFrame);

        var result = BoundedMediaMessageQueue.offer(queue, incoming);

        assertEquals(BoundedMediaMessageQueue.OfferResult.ENQUEUED_AFTER_EVICTION, result);
        assertEquals(0, staleData.refCnt());
        assertEquals(1, keyFrameData.refCnt());
        assertEquals(1, incomingData.refCnt());
        assertSame(keyFrame, queue.poll());
        assertSame(incoming, queue.poll());
        keyFrameData.release();
        incomingData.release();
    }

    @Test
    void preservesCodecAndSessionMessagesWhenDroppingVideoPackets() {
        ArrayBlockingQueue<MediaMessage> queue = new ArrayBlockingQueue<>(2);
        MediaMessage.Codec codec = new MediaMessage.Codec(CodecId.H264);
        ByteBuf staleData = packetBuffer();
        queue.add(codec);
        queue.add(new MediaMessage.Packet(1, false, false, staleData));

        var result = BoundedMediaMessageQueue.offer(
                queue, new MediaMessage.Session(1080, 1920, false));

        assertEquals(BoundedMediaMessageQueue.OfferResult.ENQUEUED_AFTER_EVICTION, result);
        assertEquals(0, staleData.refCnt());
        assertSame(codec, queue.poll());
        assertInstanceOf(MediaMessage.Session.class, queue.poll());
    }

    @Test
    void dropsIncomingMediaWhenQueueContainsOnlyControlMessages() {
        ArrayBlockingQueue<MediaMessage> queue = new ArrayBlockingQueue<>(2);
        MediaMessage.Codec codec = new MediaMessage.Codec(CodecId.H264);
        MediaMessage.Session session = new MediaMessage.Session(1080, 1920, false);
        ByteBuf incomingData = packetBuffer();
        queue.add(codec);
        queue.add(session);

        var result = BoundedMediaMessageQueue.offer(
                queue, new MediaMessage.Packet(3, false, false, incomingData));

        assertEquals(BoundedMediaMessageQueue.OfferResult.INCOMING_DROPPED, result);
        assertEquals(0, incomingData.refCnt());
        assertSame(codec, queue.poll());
        assertSame(session, queue.poll());
    }

    private static ByteBuf packetBuffer() {
        return Unpooled.buffer(1).writeByte(1);
    }
}
