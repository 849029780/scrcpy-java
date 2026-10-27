package com.jian.scrcpy.adb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Cross-platform ADB process wrapper.
 *
 * <p>The executable is resolved in this order: explicit constructor/system
 * property, bundled application ADB, ANDROID_HOME/ANDROID_SDK_ROOT, then PATH.
 * This allows packaged builds to work on Windows, macOS and Linux even when
 * the user has never installed adb globally.</p>
 */
public final class Adb {
    private static final String WINDOWS = "windows";
    private static final String MACOS = "macos";
    private static final String LINUX = "linux";

    private final String exe;
    private final String serial;

    public Adb(String exe, String serial) {
        this.exe = resolveExecutable(exe);
        this.serial = serial;
    }

    public static String resolveExecutable(String configured) {
        if (configured != null && !configured.isBlank() && !"adb".equals(configured)) {
            return configured;
        }

        String property = System.getProperty("scrcpy.adb");
        if (property != null && !property.isBlank() && !"adb".equals(property)) {
            return property;
        }

        String executable = isWindows() ? "adb.exe" : "adb";
        for (Path candidate : bundledCandidates(executable)) {
            if (Files.isRegularFile(candidate)) {
                makeExecutable(candidate);
                return candidate.toAbsolutePath().normalize().toString();
            }
        }

        for (String envName : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String sdk = System.getenv(envName);
            if (sdk == null || sdk.isBlank()) continue;
            Path candidate = Path.of(sdk, "platform-tools", executable);
            if (Files.isRegularFile(candidate)) {
                makeExecutable(candidate);
                return candidate.toAbsolutePath().normalize().toString();
            }
        }

        return executable;
    }

    private static List<Path> bundledCandidates(String executable) {
        String os = platformName();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(cwd.resolve("platform-tools").resolve(os).resolve(executable));
        candidates.add(cwd.resolve("platform-tools").resolve(executable));
        candidates.add(cwd.resolve("adb").resolve(os).resolve(executable));

        try {
            Path code = Path.of(Adb.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            Path base = Files.isDirectory(code) ? code : code.getParent();
            if (base != null) {
                candidates.add(base.resolve("platform-tools").resolve(os).resolve(executable));
                candidates.add(base.resolve("platform-tools").resolve(executable));
                Path parent = base.getParent();
                if (parent != null) {
                    candidates.add(parent.resolve("platform-tools").resolve(os).resolve(executable));
                    candidates.add(parent.resolve("platform-tools").resolve(executable));
                }
            }
        } catch (Exception ignored) {
        }
        return candidates;
    }

    private static String platformName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return MACOS;
        return LINUX;
    }

    private static boolean isWindows() {
        return WINDOWS.equals(platformName());
    }

    private static void makeExecutable(Path path) {
        if (isWindows()) return;
        try {
            path.toFile().setExecutable(true, false);
        } catch (SecurityException ignored) {
        }
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

    private Process start(List<String> command, boolean inheritIo) throws IOException {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (inheritIo) {
                builder.inheritIO();
            } else {
                builder.redirectErrorStream(true);
            }
            return builder.start();
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    /**
     * Start an asynchronous ADB command while preserving both stdout and stderr.
     *
     * <p>scrcpy-server writes its diagnostic messages to stdout on normal builds,
     * but keeping stderr separate makes the launcher robust to diagnostics emitted
     * by adb or Android framework components. Every line is prefixed with the
     * current device serial so logs from multiple devices cannot be confused.</p>
     */
    private Process startAsyncWithLogging(List<String> command) throws IOException {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            Process p = builder.start();

            startLogPump(p.getInputStream(), "stdout");
            startLogPump(p.getErrorStream(), "stderr");
            return p;
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    private void startLogPump(InputStream stream, String channel) {
        Thread thread = new Thread(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    System.out.printf("[scrcpy-java][%s][server][%s] %s%n",
                            serial == null || serial.isBlank() ? "unknown" : serial,
                            channel,
                            line);
                }
            } catch (IOException e) {
                System.err.printf("[scrcpy-java][%s][server][%s] log stream closed: %s%n",
                        serial == null || serial.isBlank() ? "unknown" : serial,
                        channel,
                        e.getMessage());
            }
        }, "scrcpy-server-log-" + channel);
        thread.setDaemon(true);
        thread.start();
    }

    private IOException unavailable(IOException cause) {
        return new IOException("ADB 不可用。请将 Android platform-tools 放到应用 platform-tools/"
                + platformName() + "/ 目录，或通过 scrcpy.adb / ANDROID_HOME / ANDROID_SDK_ROOT / PATH 指定 adb。"
                + " 当前尝试: " + exe, cause);
    }

    public String run(String... args) throws IOException, InterruptedException {
        var c = base();
        c.addAll(List.of(args));
        var p = start(c, false);
        var s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("adb failed: " + s);
        return s;
    }

    public List<AdbDevice> devices() throws IOException, InterruptedException {
        var command = List.of(exe, "devices", "-l");
        var p = start(command, false);
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
        run("reverse", "localabstract:" + a, "tcp:" + p);
    }

    public void removeReverse(int p) throws Exception {
        run("reverse", "--remove", "tcp:" + p);
    }

    public Process shellAsync(List<String> a) throws IOException {
        var c = base();
        c.add("shell");
        c.addAll(a);
        return startAsyncWithLogging(c);
    }
}
