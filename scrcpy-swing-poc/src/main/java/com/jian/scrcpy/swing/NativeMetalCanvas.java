package com.jian.scrcpy.swing;

import java.awt.Canvas;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** macOS JAWT Canvas backed by a CAMetalLayer. */
final class NativeMetalCanvas extends Canvas implements AutoCloseable {
    static { NativeLibraryLoader.load("libscrcpy_swing_metal.dylib"); }
    private volatile long handle;
    private volatile boolean closed;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    NativeMetalCanvas() {
        setBackground(java.awt.Color.WHITE);
        setVisible(true);
    }

    @Override public void addNotify() {
        super.addNotify();
        if (!closed && handle == 0) {
            try {
                handle = attach0(this);
                if (handle == 0) throw new IllegalStateException("Metal native renderer returned invalid handle");
                ready.complete(null);
            } catch (Throwable t) {
                ready.completeExceptionally(t);
                t.printStackTrace(System.err);
            }
        }
    }

    void awaitReady() {
        try { ready.get(15, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException("Metal Canvas did not become ready", e); }
    }

    boolean render(long pixelBuffer, int width, int height) {
        awaitReady();
        return !closed && handle != 0 && render0(handle, pixelBuffer, width, height);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        long h = handle; handle = 0;
        if (h != 0) destroy0(h);
    }

    private static native long attach0(NativeMetalCanvas canvas);
    private static native boolean render0(long handle, long pixelBuffer, int width, int height);
    private static native void destroy0(long handle);
}
