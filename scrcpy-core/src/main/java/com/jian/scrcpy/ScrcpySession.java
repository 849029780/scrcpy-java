package com.jian.scrcpy;

import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.media.FfmpegVideoDecoder;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ServerLauncher;
import com.jian.scrcpy.net.ScrcpyConnection;
import io.netty.channel.ChannelInboundHandler;
import java.nio.file.Path;

public final class ScrcpySession implements AutoCloseable {
    private final Adb adb;
    private final ScrcpyOptions options;
    private ServerLauncher server;
    private ScrcpyConnection connection;
    private boolean started;

    public ScrcpySession(Adb adb, ScrcpyOptions options) {
        this.adb = adb;
        this.options = options;
    }

    public void start(Path serverJar, java.util.function.Consumer<com.jian.scrcpy.media.VideoFrame> videoSink)
            throws Exception {
        if (started) return;
        if (!options.video() || options.audio() || options.control()) {
            throw new IllegalArgumentException("Basic screen mode requires video=true, audio=false, control=false");
        }

        server = new ServerLauncher(adb, options);
        connection = new ScrcpyConnection(options.localPort());

        try {
            server.start(serverJar);
            connection.open(true, false, false,
                    new FfmpegVideoDecoder(videoSink), null, null);
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
        if (server != null) {
            server.close();
            server = null;
        }
        started = false;
    }
}
