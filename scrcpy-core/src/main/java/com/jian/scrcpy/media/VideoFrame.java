package com.jian.scrcpy.media;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A decoded BGRA frame with optional buffer ownership.
 *
 * <p>The decoder may attach a recycler so large BGRA arrays can be reused
 * after JavaFX has copied the pixels. {@link #close()} is idempotent, which
 * also makes replacing/dropping the latest frame safe.</p>
 */
public final class VideoFrame implements AutoCloseable {
    private final int width;
    private final int height;
    private final byte[] bgra;
    private final long pts;
    private final Runnable recycler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public VideoFrame(int width, int height, byte[] bgra, long pts) {
        this(width, height, bgra, pts, () -> {});
    }

    public VideoFrame(int width, int height, byte[] bgra, long pts, Runnable recycler) {
        this.width = width;
        this.height = height;
        this.bgra = Objects.requireNonNull(bgra, "bgra");
        this.pts = pts;
        this.recycler = Objects.requireNonNull(recycler, "recycler");
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] bgra() {
        return bgra;
    }

    public long pts() {
        return pts;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            recycler.run();
        }
    }
}
