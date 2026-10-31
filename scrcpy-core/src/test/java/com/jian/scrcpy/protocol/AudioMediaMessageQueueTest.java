package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioMediaMessageQueueTest {

    @Test
    void overflowEvictsOldestAudioPacketAndKeepsNewestWithoutKeyFrameRecovery() {
        AudioMediaMessageQueue queue = new AudioMediaMessageQueue(2);
        MediaMessage.Codec codec = new MediaMessage.Codec(CodecId.OPUS);
        ByteBuf firstData = packetBuffer();
        ByteBuf secondData = packetBuffer();
        ByteBuf newestData = packetBuffer();

        queue.offer(codec);
        queue.offer(new MediaMessage.Packet(1, false, false, firstData));

        assertEquals(AudioMediaMessageQueue.OfferResult.ENQUEUED_AFTER_EVICTION,
                queue.offer(new MediaMessage.Packet(2, false, false, secondData)));
        assertEquals(0, firstData.refCnt());
        assertEquals(1, secondData.refCnt());

        // A subsequent non-key audio packet is accepted normally.
        assertEquals(AudioMediaMessageQueue.OfferResult.ENQUEUED_AFTER_EVICTION,
                queue.offer(new MediaMessage.Packet(3, false, false, newestData)));
        assertEquals(0, secondData.refCnt());

        assertSame(codec, queue.poll());
        MediaMessage.Packet newest = (MediaMessage.Packet) queue.poll();
        assertEquals(3, newest.pts());
        newest.data().release();
    }

    @Test
    void preservesCodecAndConfigurationPacketsWhenEvictingMedia() {
        AudioMediaMessageQueue queue = new AudioMediaMessageQueue(3);
        MediaMessage.Codec codec = new MediaMessage.Codec(CodecId.OPUS);
        ByteBuf configData = packetBuffer();
        ByteBuf oldData = packetBuffer();
        ByteBuf newData = packetBuffer();

        queue.offer(codec);
        queue.offer(new MediaMessage.Packet(-1, true, false, configData));
        queue.offer(new MediaMessage.Packet(1, false, false, oldData));

        assertEquals(AudioMediaMessageQueue.OfferResult.ENQUEUED_AFTER_EVICTION,
                queue.offer(new MediaMessage.Packet(2, false, false, newData)));
        assertEquals(0, oldData.refCnt());
        assertEquals(1, configData.refCnt());
        assertSame(codec, queue.poll());
        MediaMessage.Packet config = (MediaMessage.Packet) queue.poll();
        assertTrue(config.config());
        config.data().release();
        MediaMessage.Packet latest = (MediaMessage.Packet) queue.poll();
        assertEquals(2, latest.pts());
        latest.data().release();
    }

    @Test
    void rejectsIncomingPacketWhenQueueContainsOnlyMetadata() {
        AudioMediaMessageQueue queue = new AudioMediaMessageQueue(1);
        queue.offer(new MediaMessage.Codec(CodecId.OPUS));
        ByteBuf incoming = packetBuffer();

        assertEquals(AudioMediaMessageQueue.OfferResult.INCOMING_DROPPED,
                queue.offer(new MediaMessage.Packet(1, false, false, incoming)));
        assertEquals(0, incoming.refCnt());
        assertInstanceOf(MediaMessage.Codec.class, queue.poll());
    }

    private static ByteBuf packetBuffer() {
        return Unpooled.buffer(1).writeByte(1);
    }
}
