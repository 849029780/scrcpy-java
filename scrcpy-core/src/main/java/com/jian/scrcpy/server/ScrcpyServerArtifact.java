package com.jian.scrcpy.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

public final class ScrcpyServerArtifact {
    public static final String VERSION = "5.0.1";
    private static final String RESOURCE = "/scrcpy-server-v5.0.1.jar";
    private static final String SHA256 =
            "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536";

    private ScrcpyServerArtifact() {
    }

    public static Path resolve() throws Exception {
        String configured = System.getProperty("scrcpy.server.jar");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SCRCPY_SERVER_JAR");
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            verify(path);
            return path;
        }

        // Development checkout: keep the official server artifact in the project root.
        Path projectJar = Path.of("scrcpy-server-v5.0.1.jar").toAbsolutePath().normalize();
        if (Files.isRegularFile(projectJar)) {
            verify(projectJar);
            return projectJar;
        }

        // Packaged application: the same file is bundled as a classpath resource.
        try (InputStream resource = ScrcpyServerArtifact.class.getResourceAsStream(RESOURCE)) {
            if (resource != null) {
                Path extracted = Path.of(System.getProperty("java.io.tmpdir"),
                        "scrcpy-java", "scrcpy-server-v5.0.1.jar");
                Files.createDirectories(extracted.getParent());
                Path temporary = extracted.resolveSibling(extracted.getFileName() + ".tmp");
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
            }
        }

        throw new IllegalStateException(
                "找不到 scrcpy-server-v5.0.1.jar。请将官方 5.0.1 server 文件放在项目根目录，"
                + "或放入 scrcpy-core/src/main/resources/。不再自动从网络下载。"
                + " SHA-256: " + SHA256);
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
