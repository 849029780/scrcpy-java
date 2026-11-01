package com.jian.scrcpy.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Routes legacy Java stdout/stderr output through SLF4J/Logback.
 * Install once at application startup, before starting worker threads.
 */
public final class LoggingBootstrap {
    private static final Logger STDOUT = LoggerFactory.getLogger("legacy.stdout");
    private static final Logger STDERR = LoggerFactory.getLogger("legacy.stderr");
    private static volatile boolean installed;

    private LoggingBootstrap() {}

    public static synchronized void install() {
        if (installed) return;
        // Capture the original streams before replacing them. Logback is configured
        // with a file-only appender, so this cannot recurse back into these streams.
        System.setOut(new PrintStream(new LineLoggingOutputStream(STDOUT), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LineLoggingOutputStream(STDERR), true, StandardCharsets.UTF_8));
        installed = true;
        LoggerFactory.getLogger(LoggingBootstrap.class).info("Logback file logging initialized");
    }

    private static final class LineLoggingOutputStream extends OutputStream {
        private final Logger logger;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        private LineLoggingOutputStream(Logger logger) {
            this.logger = logger;
        }

        @Override
        public synchronized void write(int value) {
            if (value == '\n') {
                emit();
            } else if (value != '\r') {
                line.write(value);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (int i = 0; i < length; i++) write(bytes[offset + i]);
        }

        @Override
        public synchronized void flush() {
            if (line.size() > 0) emit();
        }

        private void emit() {
            String message = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (message.isBlank()) return;
            if (logger == STDERR) logger.error(message);
            else logger.info(message);
        }
    }
}
