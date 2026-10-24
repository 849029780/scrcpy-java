package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public final class ScrcpyMediaDecoder extends ByteToMessageDecoder {
    private static final long CONFIG = 1L << 62, KEY = 1L << 61, PTS_MASK = KEY - 1;
    private boolean codecRead;

    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!codecRead) {
            if (in.readableBytes() < 4) return;
            int id = in.readInt();
            codecRead = true;
            if (id == 0) {
                ctx.close();
                return;
            }
            if (id == 1) throw new IllegalStateException("server stream configuration error");
            out.add(new MediaMessage.Codec(CodecId.of(id)));
            return;
        }
        if (in.readableBytes() < 12) return;
        in.markReaderIndex();
        long a = in.readLong();
        int b = in.readInt();
        if ((a & Long.MIN_VALUE) != 0) {
            in.resetReaderIndex();
            int flags = in.readInt(), w = in.readInt(), h = in.readInt();
            out.add(new MediaMessage.Session(w, h, (flags & 1) != 0));
            return;
        }
        int n = b;
        if (n <= 0) throw new IllegalStateException("invalid packet length " + n);
        if (in.readableBytes() < n) {
            in.resetReaderIndex();
            return;
        }
        out.add(new MediaMessage.Packet((a & CONFIG) != 0 ? -1 : (a & PTS_MASK), (a & CONFIG) != 0, (a & KEY) != 0, in.readRetainedSlice(n)));
    }
}