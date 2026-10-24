package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;

public sealed interface MediaMessage permits MediaMessage.Codec, MediaMessage.Session, MediaMessage.Packet {
    record Codec(CodecId codec) implements MediaMessage {
    }

    record Session(int width, int height, boolean clientResized) implements MediaMessage {
    }

    record Packet(long pts, boolean config, boolean keyFrame, ByteBuf data) implements MediaMessage {
    }
}