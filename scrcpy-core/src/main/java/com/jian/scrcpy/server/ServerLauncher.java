package com.jian.scrcpy.server;

import com.jian.scrcpy.adb.Adb;

import java.nio.file.*;
import java.util.*;

public final class ServerLauncher implements AutoCloseable {
    public static final String REMOTE = "/data/local/tmp/scrcpy-server.jar";
    private final Adb adb;
    private final ScrcpyOptions o;
    private Process process;

    public ServerLauncher(Adb a, ScrcpyOptions o) {
        adb = a;
        this.o = o;
    }

    public String socketName() {
        return "scrcpy_%08x".formatted(o.scid());
    }

    public void start(Path jar) throws Exception {
        if (o.scid() <= 0) {
            throw new IllegalArgumentException("Invalid scrcpy scid: " + o.scid());
        }

        String scid = "%08x".formatted(o.scid());
        String socket = "scrcpy_" + scid;

        System.out.printf("[scrcpy-java] scid=%s (%d), socket=%s%n", scid, o.scid(), socket);

        adb.push(jar, REMOTE);
        try { adb.removeForward(o.localPort()); } catch (Exception ignored) {}
        try { adb.removeReverse(o.localPort()); } catch (Exception ignored) {}
        adb.reverse(o.localPort(), socket);

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

        if (!o.video()) a.add("video=false");
        if (!o.audio()) a.add("audio=false");
        if (!o.control()) a.add("control=false");

        System.out.println("[scrcpy-java] server args: " + String.join(" ", a));
        process = adb.shellAsync(a);
    }

    public void close() {
        if (process != null) process.destroy();
        try {
            adb.removeForward(o.localPort());
        } catch (Exception ignored) {
        }
    }
}
