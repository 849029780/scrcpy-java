package com.jian.scrcpy.swing;

import java.util.Locale;

final class SwingHardwareRendererFactory {
    private SwingHardwareRendererFactory() {}

    static SwingHardwareVideoRenderer create(SwingVideoSurface surface) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new SwingD3D11VideoRenderer(surface);
        if (os.contains("mac") || os.contains("darwin")) return new SwingMetalVideoRenderer(surface);
        if (os.contains("linux")) return new SwingVaapiVideoRenderer(surface);
        return null;
    }
}
