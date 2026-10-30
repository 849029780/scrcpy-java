package com.jian.scrcpy.swing;

import java.awt.Canvas;

/**
 * AWT heavyweight surface rendered by the native D3D11 backend.
 *
 * <p>The native side obtains this Canvas' HWND through JAWT. Java2D must not
 * paint video pixels into this component; D3D11 owns its swap chain.</p>
 */
public final class NativeD3D11Canvas extends Canvas {
    static {
        System.loadLibrary("scrcpy_swing_d3d11");
    }

    private long nativeHandle;

    public NativeD3D11Canvas() {
        setBackground(java.awt.Color.BLACK);
        setFocusable(true);
    }

    public void attachNativeRenderer() {
        if (!isDisplayable()) {
            throw new IllegalStateException("Canvas must be displayable before attaching D3D11");
        }
        if (nativeHandle == 0) {
            nativeHandle = attach0(this);
            if (nativeHandle == 0) {
                throw new IllegalStateException("Native D3D11 renderer returned an invalid handle");
            }
        }
    }

    public void renderFrame() {
        long handle = nativeHandle;
        if (handle != 0 && isDisplayable()) {
            render0(handle);
        }
    }

    public void detachNativeRenderer() {
        long handle = nativeHandle;
        nativeHandle = 0;
        if (handle != 0) {
            detach0(handle);
        }
    }

    private static native long attach0(NativeD3D11Canvas canvas);
    private static native void render0(long handle);
    private static native void detach0(long handle);
}
