package com.jian.scrcpy.swing;

import java.awt.Canvas;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Heavyweight AWT surface hosted by Swing. JAWT supplies its HWND to the
 * native D3D11 renderer. The D3D11 device survives peer reparenting while the
 * swap chain is detached and rebound to the new Canvas HWND.
 */
public final class NativeD3D11Canvas extends Canvas implements AutoCloseable {
    static {
        NativeLibraryLoader.load("scrcpy_swing_d3d11.dll");
    }

    private volatile long nativeHandle;
    private volatile boolean surfaceAttached;
    private volatile boolean closed;
    private final CompletableFuture<Void> deviceReady = new CompletableFuture<>();

    public NativeD3D11Canvas() {
        setBackground(java.awt.Color.BLACK);
        setFocusable(true);
        setVisible(true);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (!closed) {
            try {
                attachNativeRenderer();
            } catch (Throwable error) {
                deviceReady.completeExceptionally(error);
                error.printStackTrace(System.err);
            }
        }
    }

    @Override
    public void removeNotify() {
        long handle = nativeHandle;
        if (handle != 0 && surfaceAttached) {
            detachSurface0(handle);
            surfaceAttached = false;
        }
        super.removeNotify();
    }

    public synchronized void attachNativeRenderer() {
        if (closed) throw new IllegalStateException("D3D11 Canvas is closed");
        if (!isDisplayable()) {
            throw new IllegalStateException("Canvas must be displayable before attaching D3D11");
        }
        if (surfaceAttached) return;

        if (nativeHandle == 0) {
            nativeHandle = attach0(this);
            if (nativeHandle == 0) {
                throw new IllegalStateException("Native D3D11 renderer returned an invalid handle");
            }
        } else {
            attachSurface0(nativeHandle, this);
        }
        surfaceAttached = true;
        deviceReady.complete(null);
    }

    public long retainD3D11Device() {
        try {
            deviceReady.get(15, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("D3D11 Canvas device did not become ready", error);
        }
        long handle = nativeHandle;
        if (closed || handle == 0) throw new IllegalStateException("D3D11 Canvas is unavailable");
        long pointer = device0(handle);
        if (pointer == 0) throw new IllegalStateException("Native D3D11 device pointer is unavailable");
        return pointer;
    }

    public void releaseD3D11Device(long pointer) {
        if (pointer != 0) releaseDevicePointer0(pointer);
    }

    /**
     * Submit one D3D11 frame without waiting for GPU completion.
     *
     * @return positive token when accepted, zero when the bounded native queue
     *         is full (caller should retry), or -1 on a permanent submission failure.
     */
    public long submitHardwareFrame(long texture, long arrayIndex, int width, int height,
                                    int colorSpace, int colorRange) {
        long handle = nativeHandle;
        if (closed || !surfaceAttached || handle == 0 || !isDisplayable()) return -1;
        return submitHardware0(handle, texture, arrayIndex, width, height, colorSpace, colorRange);
    }

    /** Returns a completed token; negative tokens indicate presentation failure. */
    public long pollCompletedFrame() {
        long handle = nativeHandle;
        return handle == 0 ? 0 : pollCompleted0(handle);
    }

    /** Retained for the standalone native-surface smoke test. */
    public void renderFrame() {
        long handle = nativeHandle;
        if (!closed && surfaceAttached && handle != 0 && isDisplayable()) {
            render0(handle);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        long handle = nativeHandle;
        nativeHandle = 0;
        surfaceAttached = false;
        if (handle != 0) destroy0(handle);
        deviceReady.completeExceptionally(new IllegalStateException("D3D11 Canvas closed"));
    }

    private static native long attach0(NativeD3D11Canvas canvas);
    private static native void attachSurface0(long handle, NativeD3D11Canvas canvas);
    private static native void detachSurface0(long handle);
    private static native long device0(long handle);
    private static native void releaseDevicePointer0(long pointer);
    private static native long submitHardware0(long handle, long texture, long arrayIndex,
                                                int width, int height, int colorSpace, int colorRange);
    private static native long pollCompleted0(long handle);
    private static native void render0(long handle);
    private static native void destroy0(long handle);
}
