package com.jian.scrcpy.swing;

import java.awt.Canvas;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** X11/JAWT Canvas presented directly by VA-API. */
final class NativeVaapiCanvas extends Canvas implements AutoCloseable {
    static { NativeLibraryLoader.load("libscrcpy_swing_vaapi.so"); }
    private volatile long handle;
    private volatile boolean closed;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    NativeVaapiCanvas() {
        setBackground(java.awt.Color.WHITE);
        setVisible(true);
    }

    @Override public void addNotify() {
        super.addNotify();
        if (!closed && handle == 0) {
            try {
                handle = attach0(this);
                if (handle == 0) throw new IllegalStateException("VAAPI native renderer returned invalid handle");
                ready.complete(null);
            } catch (Throwable t) {
                ready.completeExceptionally(t);
                t.printStackTrace(System.err);
            }
        }
    }

    long retainVaDisplay() {
        try { ready.get(15, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException("VAAPI Canvas did not become ready", e); }
        long h = handle;
        if (closed || h == 0) throw new IllegalStateException("VAAPI Canvas unavailable");
        return display0(h);
    }

    boolean render(long surface, int width, int height) {
        long h = handle;
        return !closed && h != 0 && render0(h, surface, width, height);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        long h = handle; handle = 0;
        if (h != 0) destroy0(h);
    }

    private static native long attach0(NativeVaapiCanvas canvas);
    private static native long display0(long handle);
    private static native boolean render0(long handle, long surface, int width, int height);
    private static native void destroy0(long handle);
}
