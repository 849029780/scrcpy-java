package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public final class ScrcpyMediaDecoder extends ByteToMessageDecoder {
    private static final long CONFIG = 1L << 63;
    private static final long KEY = 1L << 62;
    private static final long PTS_MASK = CONFIG - 1;

    private boolean codecRead;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!codecRead) {
            if (in.readableBytes() < 4) return;

            int id = in.readInt();
            codecRead = true;

            if (id == 0) throw new IllegalStateException("scrcpy video stream disabled");
            if (id == 1) throw new IllegalStateException("scrcpy server stream configuration error");

            CodecId codec = CodecId.of(id);
            if (codec != CodecId.H264 && codec != CodecId.H265 && codec != CodecId.AV1
                    && codec != CodecId.VP8 && codec != CodecId.VP9) {
                throw new IllegalArgumentException("Not a video codec: " + codec);
            }

            System.out.printf("[scrcpy-java] video codec: %s (0x%08x)%n", codec, id);
            out.add(new MediaMessage.Codec(codec));
            return;
        }

        if (in.readableBytes() < 12) return;

        in.markReaderIndex();
        long header = in.readLong();
        int size = in.readInt();

        if ((header & Long.MIN_VALUE) != 0) {
            in.resetReaderIndex();
            int flags = in.readInt();
            int width = in.readInt();
            int height = in.readInt();

            System.out.printf("[scrcpy-java] video session: %dx%d flags=0x%08x%n",
                    width, height, flags);

            out.add(new MediaMessage.Session(width, height, (flags & 1) != 0));
            return;
        }

        if (size < 0) throw new IllegalStateException("invalid scrcpy packet length: " + size);
        if (in.readableBytes() < size) {
            in.resetReaderIndex();
            return;
        }

        long pts = header & PTS_MASK;
        boolean config = (header & CONFIG) != 0;
        boolean keyFrame = (header & KEY) != 0;

        out.add(new MediaMessage.Packet(
                config ? -1 : pts,
                config,
                keyFrame,
                in.readRetainedSlice(size)));
    }
}
