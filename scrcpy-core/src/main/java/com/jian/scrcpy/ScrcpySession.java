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
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<DeviceMessage>> deviceMessageListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ClipboardAckTracker clipboardAcks = new ClipboardAckTracker();
    private volatile boolean started;
    private boolean closing;

    public ScrcpySession(Adb adb, ScrcpyOptions options) {
        this.adb = adb;
        this.options = options;
    }

    public void start(Path serverJar, Consumer<com.jian.scrcpy.media.VideoFrame> videoSink)
            throws Exception {
        start(serverJar, videoSink, null);
    }

    public synchronized void start(Path serverJar,
                      Consumer<com.jian.scrcpy.media.VideoFrame> videoSink,
                      Consumer<AudioFrame> audioSink)
            throws Exception {
        if (started) return;
        server = new ServerLauncher(adb, options);
        connection = new ScrcpyConnection(options.localPort());

        try {
            if (options.video()) {
                videoDecoder = new FfmpegVideoDecoder(videoSink);
            }
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
                            deviceMessageListeners.forEach(listener -> listener.accept(message));
                        }
                    } : null,
                    options.video(),
                    options.audio(),
                    options.control());

            server.start(serverJar, connection.localPort());

            if (options.turnScreenOff() || options.startApp() != null) {
                if (!options.control()) {
                    throw new IllegalArgumentException(
                            "turnScreenOff/startApp require control=true");
                }
                awaitControlReady(5_000);
                if (options.startApp() != null
                        && !sendControl(new ControlMessages.StartApp(options.startApp()))) {
                    throw new IllegalStateException("Could not start app: " + options.startApp());
                }
                if (options.turnScreenOff()
                        && !sendControl(new ControlMessages.DisplayPower(false))) {
                    throw new IllegalStateException("Could not request device screen off");
                }
            }

            started = true;
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private void awaitControlReady(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!controlReady()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for scrcpy control socket");
            }
            Thread.sleep(10);
        }
    }

    public String deviceName() {
        return connection == null ? null : connection.deviceName();
    }

    public void onDeviceMessage(Consumer<DeviceMessage> sink) {
        deviceMessageSink = Objects.requireNonNullElse(sink, message -> {});
    }

    public AutoCloseable addDeviceMessageListener(Consumer<DeviceMessage> listener) {
        Objects.requireNonNull(listener, "listener");
        deviceMessageListeners.add(listener);
        return () -> deviceMessageListeners.remove(listener);
    }

    public boolean controlReady() {
        Channel channel = connection == null ? null : connection.control();
        return channel != null && channel.isActive();
    }

    public boolean sendControl(ControlMessages.Message message) {
        Objects.requireNonNull(message, "message");
        if (closing) return false;
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
    public synchronized void close() {
        if (closing) return;
        closing = true;
        try {
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
        deviceMessageListeners.clear();
        started = false;
        } finally {
            closing = false;
        }
    }
}
