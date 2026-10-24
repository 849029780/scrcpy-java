package com.jian.scrcpy.adb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class Adb {
    private final String exe;
    private final String serial;

    public Adb(String exe, String serial) {
        this.exe = Objects.requireNonNullElse(exe, "adb");
        this.serial = serial;
    }

    private List<String> base() {
        var x = new ArrayList<String>();
        x.add(exe);
        if (serial != null && !serial.isBlank()) {
            x.add("-s");
            x.add(serial);
        }
        return x;
    }

    public String run(String... args) throws IOException, InterruptedException {
        var c = base();
        c.addAll(List.of(args));
        var p = new ProcessBuilder(c).redirectErrorStream(true).start();
        var s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("adb failed: " + s);
        return s;
    }

    public List<AdbDevice> devices() throws IOException, InterruptedException {
        var p = new ProcessBuilder(exe, "devices", "-l").redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("adb devices failed: " + output);

        var result = new ArrayList<AdbDevice>();
        for (String line : output.lines().toList()) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices attached")) continue;

            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;

            String model = null;
            String transport = parts[0].contains(":") ? "tcpip" : "usb";
            for (String part : parts) {
                if (part.startsWith("model:")) model = part.substring(6).replace('_', ' ');
            }
            result.add(new AdbDevice(parts[0], parts[1], model, transport));
        }
        return result;
    }

    public void push(Path l, String r) throws Exception {
        run("push", l.toAbsolutePath().toString(), r);
    }

    public void forward(int p, String a) throws Exception {
        run("forward", "tcp:" + p, "localabstract:" + a);
    }

    public void removeForward(int p) throws Exception {
        run("forward", "--remove", "tcp:" + p);
    }

    public void reverse(int p, String a) throws Exception {
        run("reverse", "tcp:" + p, "localabstract:" + a);
    }

    public void removeReverse(int p) throws Exception {
        run("reverse", "--remove", "tcp:" + p);
    }

    public Process shellAsync(List<String> a) throws IOException {
        var c = base();
        c.add("shell");
        c.addAll(a);
        return new ProcessBuilder(c).inheritIO().start();
    }
}