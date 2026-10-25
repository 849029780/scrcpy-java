package com.jian.scrcpy;

import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.media.AudioFrame;
import com.jian.scrcpy.media.FfmpegAudioDecoder;
import com.jian.scrcpy.media.FfmpegVideoDecoder;
import com.jian.scrcpy.net.ScrcpyConnection;
import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.protocol.ClipboardAckTracker;
import com.jian.scrcpy.protocol.ControlMessageQueue;
import com.jian.scrcpy.protocol.DeviceMessage;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ServerLauncher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

public final class ScrcpySession implements AutoCloseable {
    private final Adb adb;
    private final ScrcpyOptions options;
    private ServerLauncher server;
    private ScrcpyConnection connection;
    private FfmpegVideoDecoder videoDecoder;
    private FfmpegAudioDecoder audioDecoder;
    private volatile Consumer<DeviceMessage> deviceMessageSink = message -> {};
    private final ClipboardAckTracker clipboardAcks = new ClipboardAckTracker();
    private boolean started;

    public ScrcpySession(Adb adb, ScrcpyOptions options) {
        this.adb = adb;
        this.options = options;
    }

    public void start(Path serverJar, Consumer<com.jian.scrcpy.media.VideoFrame> videoSink)
            throws Exception {
        start(serverJar, videoSink, null);
    }

    public void start(Path serverJar,
                      Consumer<com.jian.scrcpy.media.VideoFrame> videoSink,
                      Consumer<AudioFrame> audioSink)
            throws Exception {
        if (started) return;
        if (!options.video()) {
            throw new IllegalArgumentException("Current mode requires video=true");
        }

        server = new ServerLauncher(adb, options);
        connection = new ScrcpyConnection(options.localPort());

        try {
            videoDecoder = new FfmpegVideoDecoder(videoSink);
            if (options.audio()) {
                audioDecoder = new FfmpegAudioDecoder(audioSink == null ? f -> {} : audioSink);
            }

            connection.listen(
                    videoDecoder,
                    audioDecoder,
                    options.control() ? new SimpleChannelInboundHandler<DeviceMessage>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DeviceMessage message) {
                            if (message instanceof DeviceMessage.AckClipboard ack) {
                                clipboardAcks.acknowledge(ack.sequence());
                            }
                            deviceMessageSink.accept(message);
                        }
                    } : null,
                    options.video(),
                    options.audio(),
                    options.control());

            server.start(serverJar, connection.localPort());
            started = true;
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public String deviceName() {
        return connection == null ? null : connection.deviceName();
    }

    public void onDeviceMessage(Consumer<DeviceMessage> sink) {
        deviceMessageSink = Objects.requireNonNullElse(sink, message -> {});
    }

    public boolean controlReady() {
        Channel channel = connection == null ? null : connection.control();
        return channel != null && channel.isActive();
    }

    public boolean sendControl(ControlMessages.Message message) {
        Objects.requireNonNull(message, "message");
        Channel channel = connection == null ? null : connection.control();
        ControlMessageQueue queue = connection == null ? null : connection.controlQueue();
        if (channel == null || !channel.isActive() || queue == null) return false;
        return queue.submit(message);
    }

    public long setClipboard(String text, boolean paste, Consumer<Long> onAck) {
        long sequence = clipboardAcks.nextSequence();
        clipboardAcks.track(sequence, onAck);
        if (!sendControl(new ControlMessages.ClipboardSet(sequence, paste, text))) {
            clipboardAcks.acknowledge(sequence);
            return 0;
        }
        return sequence;
    }

    public int pendingClipboardAcks() {
        return clipboardAcks.pendingCount();
    }

    public void stop() {
        close();
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (videoDecoder != null) {
            videoDecoder.close();
            videoDecoder = null;
        }
        if (audioDecoder != null) {
            audioDecoder.close();
            audioDecoder = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        clipboardAcks.clear();
        started = false;
    }
}
