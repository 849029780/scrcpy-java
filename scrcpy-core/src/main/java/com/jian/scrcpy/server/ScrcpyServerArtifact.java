package com.jian.scrcpy.server;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class ScrcpyServerArtifact {
    public static final String VERSION = "5.0.1";
    private static final String URL =
            "https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1";
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
            Path path = Path.of(configured).toAbsolutePath();
            verify(path);
            return path;
        }

        Path[] candidates = {
                Path.of("scrcpy-server-v5.0.1"),
                Path.of("scrcpy-server-v5.0.1.jar"),
                Path.of("scrcpy-server")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                verify(candidate);
                return candidate.toAbsolutePath();
            }
        }

        Path cache = Path.of(System.getProperty("user.home"), ".scrcpy-java",
                "scrcpy-server-v5.0.1");
        if (!Files.isRegularFile(cache) || !matchesSha256(cache)) {
            Files.createDirectories(cache.getParent());
            Path tmp = cache.resolveSibling(cache.getFileName() + ".download");
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("Cannot download scrcpy-server: HTTP " + response.statusCode());
            }
            if (!matchesSha256(tmp)) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("scrcpy-server SHA-256 mismatch");
            }
            Files.move(tmp, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        verify(cache);
        return cache;
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
