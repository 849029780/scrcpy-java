package com.jian.scrcpy.control;

import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.DeviceMessage;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * UI-independent bidirectional clipboard bridge for one scrcpy session.
 *
 * Android -> frontend is delivered by DeviceMessage.Clipboard.
 * Frontend -> Android is sent with SET_CLIPBOARD (paste=false).
 */
public final class ClipboardController {
    private final Consumer<ControlMessages.Message> sink;
    private volatile Consumer<String> remoteClipboardSink = text -> {};

    public ClipboardController(Consumer<ControlMessages.Message> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public void onRemoteClipboard(Consumer<String> sink) {
        remoteClipboardSink = Objects.requireNonNullElse(sink, text -> {});
    }

    public void setRemoteClipboard(String text) {
        if (text == null) return;
        // sequence=0: autosync write, no ACK required; paste=false: only copy.
        sink.accept(new ControlMessages.ClipboardSet(0, false, text));
    }

    public void requestRemoteClipboard() {
        sink.accept(new ControlMessages.GetClipboard(0));
    }

    public void handle(DeviceMessage message) {
        if (message instanceof DeviceMessage.Clipboard clipboard) {
            remoteClipboardSink.accept(clipboard.text());
        }
    }
}
