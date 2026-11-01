package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import com.jian.scrcpy.metrics.SessionMetrics;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public final class ScrcpyAudioDecoder extends ByteToMessageDecoder {
    private static final long CONFIG = 1L << 62;
    private static final long KEY = 1L << 61;
    private static final long PTS_MASK = KEY - 1;
    private boolean codecRead;
    private final String sessionLabel;
    private final SessionMetrics metrics;

    public ScrcpyAudioDecoder() {
        this(null, null);
    }

    public ScrcpyAudioDecoder(String sessionLabel) {
        this(sessionLabel, null);
    }

    public ScrcpyAudioDecoder(String sessionLabel, SessionMetrics metrics) {
        this.sessionLabel = sessionLabel == null || sessionLabel.isBlank() ? "unknown" : sessionLabel;
        this.metrics = metrics;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!codecRead) {
            if (in.readableBytes() < 4) return;
            int id = in.readInt();
            codecRead = true;
            if (id == 0) throw new IllegalStateException("scrcpy audio stream disabled");
            if (id == 1) throw new IllegalStateException("scrcpy server stream configuration error");
            CodecId codec = CodecId.of(id);
            if (codec != CodecId.OPUS && codec != CodecId.AAC && codec != CodecId.FLAC) {
                throw new IllegalArgumentException("Not an audio codec: " + codec);
            }
            System.out.printf("[scrcpy-java][%s] audio codec: %s (0x%08x)%n", sessionLabel, codec, id);
            out.add(new MediaMessage.Codec(codec));
            return;
        }
        if (in.readableBytes() < 12) return;
        in.markReaderIndex();
        long header = in.readLong();
        int size = in.readInt();
        if (size < 0) throw new IllegalStateException("invalid scrcpy audio packet length: " + size);
        if (in.readableBytes() < size) {
            in.resetReaderIndex();
            return;
        }
        long pts = header & PTS_MASK;
        boolean config = (header & CONFIG) != 0;
        boolean keyFrame = (header & KEY) != 0;
        if (metrics != null) metrics.recordAudioPacket(config, size);
        out.add(new MediaMessage.Packet(config ? -1 : pts, config, keyFrame, in.readRetainedSlice(size)));
    }
}
