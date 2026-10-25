package com.jian.scrcpy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.jian.scrcpy.protocol.ControlMessages.*;

public final class ControlMessageEncoder extends MessageToByteEncoder<Message> {
    public static final int MAX_MESSAGE_SIZE = 1 << 18;
    public static final int MAX_TEXT_LENGTH = 300;
    public static final int MAX_CLIPBOARD_TEXT_LENGTH = MAX_MESSAGE_SIZE - 14;
    public static final int MAX_SCAN_FILE_PATH_LENGTH = 256;

    private static byte[] utf8(String s, int max) {
        if (s == null || s.isEmpty()) return new byte[0];
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) return b;
        int n = max;
        while (n > 0 && (b[n] & 0xC0) == 0x80) n--;
        return Arrays.copyOf(b, n);
    }

    private static void pos(ByteBuf o, Position p) {
        o.writeInt(p.x()).writeInt(p.y()).writeShort(p.screenWidth()).writeShort(p.screenHeight());
    }

    private static int u16fp(float v) {
        float clamped = Math.max(0f, Math.min(1f, v));
        return Math.round(clamped * 65535f);
    }

    private static int i16fp(float v) {
        float clamped = Math.max(-1f, Math.min(1f, v));
        return clamped >= 0 ? Math.round(clamped * 32767f) : Math.round(clamped * 32768f);
    }

    @Override
    protected void encode(ChannelHandlerContext c, Message m, ByteBuf o) {
        switch (m) {
            case Keycode x ->
                    o.writeByte(ControlMessageType.INJECT_KEYCODE.ordinal()).writeByte(x.action())
                            .writeInt(x.keycode()).writeInt(x.repeat()).writeInt(x.metaState());
            case Text x -> {
                byte[] b = utf8(x.text(), MAX_TEXT_LENGTH);
                o.writeByte(ControlMessageType.INJECT_TEXT.ordinal()).writeInt(b.length).writeBytes(b);
            }
            case Touch x -> {
                o.writeByte(ControlMessageType.INJECT_TOUCH_EVENT.ordinal()).writeByte(x.action()).writeLong(x.pointerId());
                pos(o, x.position());
                o.writeShort(u16fp(x.pressure())).writeInt(x.actionButton()).writeInt(x.buttons());
            }
            case Scroll x -> {
                o.writeByte(ControlMessageType.INJECT_SCROLL_EVENT.ordinal());
                pos(o, x.position());
                o.writeShort(i16fp(x.hscroll() / 16f)).writeShort(i16fp(x.vscroll() / 16f)).writeInt(x.buttons());
            }
            case BackOrScreenOn x ->
                    o.writeByte(ControlMessageType.BACK_OR_SCREEN_ON.ordinal()).writeByte(x.action());
            case Simple x -> o.writeByte(x.type().ordinal());
            case GetClipboard x ->
                    o.writeByte(ControlMessageType.GET_CLIPBOARD.ordinal()).writeByte(x.copyKey());
            case ClipboardSet x -> {
                byte[] b = utf8(x.text(), MAX_CLIPBOARD_TEXT_LENGTH);
                o.writeByte(ControlMessageType.SET_CLIPBOARD.ordinal()).writeLong(x.sequence())
                        .writeByte(x.paste() ? 1 : 0).writeInt(b.length).writeBytes(b);
            }
            case DisplayPower x ->
                    o.writeByte(ControlMessageType.SET_DISPLAY_POWER.ordinal()).writeByte(x.on() ? 1 : 0);
            case UhidCreate x -> {
                byte[] name = utf8(x.name(), 127);
                byte[] descriptor = x.reportDescriptor();
                o.writeByte(ControlMessageType.UHID_CREATE.ordinal())
                        .writeShort(x.id()).writeShort(x.vendorId()).writeShort(x.productId())
                        .writeByte(name.length).writeBytes(name)
                        .writeShort(descriptor.length).writeBytes(descriptor);
            }
            case UhidInput x -> {
                byte[] data = x.data();
                o.writeByte(ControlMessageType.UHID_INPUT.ordinal()).writeShort(x.id())
                        .writeShort(data.length).writeBytes(data);
            }
            case UhidDestroy x ->
                    o.writeByte(ControlMessageType.UHID_DESTROY.ordinal()).writeShort(x.id());
            case StartApp x -> {
                byte[] b = utf8(x.name(), MAX_MESSAGE_SIZE - 5);
                o.writeByte(ControlMessageType.START_APP.ordinal()).writeInt(b.length).writeBytes(b);
            }
            case CameraSetTorch x ->
                    o.writeByte(ControlMessageType.CAMERA_SET_TORCH.ordinal()).writeByte(x.on() ? 1 : 0);
            case ResizeDisplay x ->
                    o.writeByte(ControlMessageType.RESIZE_DISPLAY.ordinal()).writeShort(x.width()).writeShort(x.height());
            case ScanFile x -> {
                byte[] b = utf8(x.path(), MAX_SCAN_FILE_PATH_LENGTH);
                o.writeByte(ControlMessageType.SCAN_FILE.ordinal()).writeInt(b.length).writeBytes(b);
            }
        }
    }
}
