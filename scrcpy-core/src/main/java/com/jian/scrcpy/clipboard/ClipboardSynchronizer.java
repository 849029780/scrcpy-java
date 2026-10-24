package com.jian.scrcpy.clipboard;

import com.jian.scrcpy.protocol.*;
import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClipboardSynchronizer {
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<Long, Runnable> pending = new ConcurrentHashMap<>();

    public long set(Channel c, String text, boolean paste, Runnable ack) {
        long s = sequence.incrementAndGet();
        if (ack != null) pending.put(s, ack);
        c.writeAndFlush(new ControlMessages.ClipboardSet(s, paste, text));
        return s;
    }

    public void onDeviceMessage(DeviceMessage m) {
        if (m instanceof DeviceMessage.AckClipboard a) {
            var r = pending.remove(a.sequence());
            if (r != null) r.run();
        }
    }
}