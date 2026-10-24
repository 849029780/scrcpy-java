package com.jian.scrcpy;

import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.media.AudioFrame;
import com.jian.scrcpy.media.FfmpegAudioDecoder;
import com.jian.scrcpy.media.FfmpegVideoDecoder;
import com.jian.scrcpy.net.ScrcpyConnection;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ServerLauncher;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class ScrcpySession implements AutoCloseable {
    private final Adb adb;
    private final ScrcpyOptions options;
    private ServerLauncher server;
    private ScrcpyConnection connection;
    private FfmpegVideoDecoder videoDecoder;
    private FfmpegAudioDecoder audioDecoder;
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
                    null,
                    options.video(),
                    options.audio(),
                    options.control());

            server.start(serverJar);
            started = true;
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public String deviceName() {
        return connection == null ? null : connection.deviceName();
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
        started = false;
    }
}
