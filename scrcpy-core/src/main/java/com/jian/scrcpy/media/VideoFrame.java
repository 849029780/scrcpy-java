package com.jian.scrcpy.media;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A decoded BGRA frame backed by a direct buffer with optional ownership.
 *
 * <p>The decoder attaches a recycler so the same large native buffer can be
 * reused after JavaFX no longer displays it. Keeping the pixels off-heap also
 * avoids the native-BGRA to Java byte[] copy on every frame.</p>
 */
public final class VideoFrame implements AutoCloseable {
    private final int width;
    private final int height;
    private final ByteBuffer bgra;
    private final long pts;
    private final Runnable recycler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public VideoFrame(int width, int height, ByteBuffer bgra, long pts, Runnable recycler) {
        this.width = width;
        this.height = height;
        this.bgra = Objects.requireNonNull(bgra, "bgra");
        this.pts = pts;
        this.recycler = Objects.requireNonNull(recycler, "recycler");
    }

    public int width() { return width; }
    public int height() { return height; }

    /**
     * Direct BYTE_BGRA_PRE-compatible storage. Video frames are opaque, so
     * FFmpeg's BGRA output (alpha=255) is also valid premultiplied BGRA.
     */
    public ByteBuffer bgra() { return bgra; }

    public long pts() { return pts; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            recycler.run();
        }
    }
}
