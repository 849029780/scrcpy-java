package com.jian.scrcpy.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the external scrcpy server JAR bundled beside the desktop application.
 */
public final class ScrcpyServerArtifact {
    public static final String VERSION = "5.0.1";
    private static final String FILE_NAME = "scrcpy-server-v5.0.1.jar";
    private static final String SHA256 =
            "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536";

    private ScrcpyServerArtifact() {
    }

    /**
     * Finds the server in the application's lib directory. The packaged layout is:
     * app/
     *   scrcpy-swing-poc-*.jar
     *   lib/scrcpy-server-v5.0.1.jar
     */
    public static Path resolve() throws Exception {
        for (Path candidate : candidates()) {
            if (Files.isRegularFile(candidate)) {
                verify(candidate);
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Bundled scrcpy server not found. Expected "
                + "lib/" + FILE_NAME + " beside the application.");
    }

    private static List<Path> candidates() {
        List<Path> result = new ArrayList<>();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        result.add(cwd.resolve("lib").resolve(FILE_NAME));
        try {
            Path code = Path.of(ScrcpyServerArtifact.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path base = Files.isDirectory(code) ? code : code.getParent();
            if (base != null) {
                result.add(base.resolve("lib").resolve(FILE_NAME));
                Path parent = base.getParent();
                if (parent != null) result.add(parent.resolve("lib").resolve(FILE_NAME));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static void verify(Path path) throws Exception {
        if (!Files.isRegularFile(path) || !matchesSha256(path)) {
            throw new IllegalArgumentException("scrcpy server missing or SHA-256 mismatch: " + path);
        }
    }

    private static boolean matchesSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) >= 0; ) {
                if (n > 0) digest.update(buffer, 0, n);
            }
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte b : digest.digest()) actual.append("%02x".formatted(b));
        return SHA256.equals(actual.toString());
    }
}
