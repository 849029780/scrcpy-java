package com.jian.scrcpy.swing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the native presentation backend bundled in this module's resources.
 *
 * <p>The library is extracted to a private temporary file because
 * {@link System#loadLibrary(String)} cannot load a library directly from a JAR.</p>
 */
final class NativeLibraryLoader {
    private static final Object LOCK = new Object();
    private static String loadedLibrary;

    private NativeLibraryLoader() {
    }

    static void load(String libraryFileName) {
        synchronized (LOCK) {
            if (loadedLibrary != null) {
                if (!loadedLibrary.endsWith("/" + libraryFileName)
                        && !loadedLibrary.endsWith("\\" + libraryFileName)) {
                    throw new IllegalStateException("A different Swing native library is already loaded: "
                            + loadedLibrary);
                }
                return;
            }

            String platform = platform();
            String resource = "/native/" + platform + "/" + libraryFileName;
            try {
                Path library = extract(resource, libraryFileName, platform);
                System.load(library.toAbsolutePath().toString());
                loadedLibrary = library.toAbsolutePath().toString();
                System.out.println("[scrcpy-swing] loaded native renderer: " + platform
                        + " (" + loadedLibrary + ")");
            } catch (IOException | UnsatisfiedLinkError error) {
                UnsatisfiedLinkError failure = new UnsatisfiedLinkError(
                        "Unable to load bundled native renderer " + resource + ": " + error);
                failure.initCause(error);
                throw failure;
            }
        }
    }

    private static Path extract(String resource, String fileName, String platform) throws IOException {
        // An explicit directory is useful for native development without rebuilding the JAR.
        String override = System.getProperty("scrcpy.native.dir");
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override).resolve(fileName);
            if (Files.isRegularFile(candidate)) return candidate;
        }

        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Native resource not found: " + resource);
            }
            String suffix = fileName.substring(fileName.lastIndexOf('.'));
            Path directory = Path.of(System.getProperty("java.io.tmpdir"),
                    "scrcpy-java", "native", platform);
            Files.createDirectories(directory);
            Path extracted = Files.createTempFile(directory, "scrcpy-", suffix);
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            return extracted;
        }
    }

    private static String platform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os;
        if (osName.contains("win")) os = "windows";
        else if (osName.contains("mac") || osName.contains("darwin")) os = "macos";
        else if (osName.contains("linux")) os = "linux";
        else throw new UnsupportedOperationException("Unsupported operating system: " + osName);

        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = switch (architecture) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new UnsupportedOperationException(
                    "Unsupported CPU architecture: " + architecture);
        };
        return os + "-" + arch;
    }
}
