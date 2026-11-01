package com.jian.scrcpy.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Resolves the scrcpy server bundled by the desktop client on its runtime classpath.
 */
public final class ScrcpyServerArtifact {
    public static final String VERSION = "5.0.1";
    private static final String RESOURCE = "scrcpy-server-v5.0.1.jar";
    private static final String SHA256 =
            "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536";

    private ScrcpyServerArtifact() {
    }

    /**
     * Extracts the server bundled in scrcpy-swing-poc/src/main/resources to a
     * temporary file so ADB can push it to the Android device.
     */
    public static Path resolve() throws Exception {
        ClassLoader classLoader = ScrcpyServerArtifact.class.getClassLoader();
        try (InputStream resource = classLoader.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "Classpath resource " + RESOURCE + " was not found. "
                        + "Make sure it is included in scrcpy-swing-poc/src/main/resources.");
            }

            Path extracted = Path.of(System.getProperty("java.io.tmpdir"),
                    "scrcpy-java", RESOURCE);
            Files.createDirectories(extracted.getParent());
            Path temporary = Files.createTempFile(extracted.getParent(),
                    "scrcpy-server-", ".tmp");
            try {
                Files.copy(resource, temporary, StandardCopyOption.REPLACE_EXISTING);
                verify(temporary);
                try {
                    Files.move(temporary, extracted, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, extracted, StandardCopyOption.REPLACE_EXISTING);
                }
                verify(extracted);
                return extracted;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void verify(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("scrcpy server not found: " + path);
        }
        if (!matchesSha256(path)) {
            throw new IllegalArgumentException("scrcpy server SHA-256 mismatch: " + path);
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
