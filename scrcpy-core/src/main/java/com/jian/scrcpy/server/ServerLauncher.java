package com.jian.scrcpy.server;

import com.jian.scrcpy.adb.Adb;

import java.nio.file.*;
import java.util.*;

public final class ServerLauncher implements AutoCloseable {
    public static final String REMOTE = "/data/local/tmp/scrcpy-server.jar";
    private final Adb adb;
    private final ScrcpyOptions o;
    private Process process;
    private int reversePort;

    public ServerLauncher(Adb a, ScrcpyOptions o) {
        adb = a;
        this.o = o;
    }

    public String socketName() {
        return "scrcpy_%08x".formatted(o.scid());
    }

    public void start(Path jar) throws Exception {
        start(jar, o.localPort());
    }

    public void start(Path jar, int localPort) throws Exception {
        if (o.scid() <= 0) {
            throw new IllegalArgumentException("Invalid scrcpy scid: " + o.scid());
        }

        String scid = "%08x".formatted(o.scid());
        String socket = "scrcpy_" + scid;

        System.out.printf("[scrcpy-java] scid=%s (%d), socket=%s%n", scid, o.scid(), socket);

        if (localPort <= 0 || localPort > 65535) {
            throw new IllegalArgumentException("Invalid local tunnel port: " + localPort);
        }
        reversePort = localPort;

        adb.push(jar, REMOTE);
        try { adb.removeForward(localPort); } catch (Exception ignored) {}
        try { adb.removeReverse(localPort); } catch (Exception ignored) {}
        adb.reverse(localPort, socket);

        var a = buildServerArgs(o, scid);

        System.out.println("[scrcpy-java] server args: " + String.join(" ", a));
        process = adb.shellAsync(a);
    }

    static ArrayList<String> buildServerArgs(ScrcpyOptions o, String scid) {
        var a = new ArrayList<String>();
        Collections.addAll(
                a,
                "CLASSPATH=" + REMOTE,
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                "5.0.1",
                "scid=" + scid,
                "log_level=info",
                "tunnel_forward=false");

        if (!o.video()) {
            a.add("video=false");
        } else {
            if (o.videoCodec() != null && !o.videoCodec().isBlank()
                    && !"h264".equalsIgnoreCase(o.videoCodec())) {
                a.add("video_codec=" + o.videoCodec());
            }
            if (o.videoBitRate() > 0) {
                a.add("video_bit_rate=" + requireIntRange("video_bit_rate", o.videoBitRate()));
            }
            if (o.maxSize() > 0) {
                a.add("max_size=" + o.maxSize());
            }
            if (o.maxFps() != null && !o.maxFps().isBlank()) {
                a.add("max_fps=" + o.maxFps());
            }
            if (o.crop() != null && !o.crop().isBlank()) {
                a.add("crop=" + o.crop());
            }
        }

        if (!o.audio()) {
            a.add("audio=false");
        } else {
            if (o.audioCodec() != null && !o.audioCodec().isBlank()
                    && !"opus".equalsIgnoreCase(o.audioCodec())) {
                a.add("audio_codec=" + o.audioCodec());
            }
            if (o.audioBitRate() > 0) {
                a.add("audio_bit_rate=" + requireIntRange("audio_bit_rate", o.audioBitRate()));
            }
        }

        if (!o.control()) {
            a.add("control=false");
        }

        // These two are server-side options in scrcpy 5.0.1.
        if (o.showTouches()) {
            a.add("show_touches=true");
        }
        if (o.stayAwake()) {
            a.add("stay_awake=true");
        }

        // turn_screen_off is intentionally NOT a server option.
        // scrcpy's desktop client sends SET_DISPLAY_POWER(false) through the
        // control socket after all sockets are initialized.
        return a;
    }

    private static String requireIntRange(String name, long value) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " exceeds scrcpy server int range: " + value);
        }
        return Long.toString(value);
    }

    public void close() {
        if (process != null) process.destroy();
        try {
            if (reversePort > 0) adb.removeReverse(reversePort);
        } catch (Exception ignored) {
        }
    }
}
