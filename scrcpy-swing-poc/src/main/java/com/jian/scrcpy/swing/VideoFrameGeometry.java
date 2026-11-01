package com.jian.scrcpy.swing;

import java.awt.Dimension;
import java.awt.Rectangle;

/**
 * Single source of truth for fitting decoded video frames into Swing surfaces.
 * All renderers preserve the complete frame and its aspect ratio.
 */
final class VideoFrameGeometry {
    private VideoFrameGeometry() {}

    static Dimension fit(int frameWidth, int frameHeight, int maxWidth, int maxHeight) {
        if (maxWidth <= 0 || maxHeight <= 0) return new Dimension(1, 1);
        if (frameWidth <= 0 || frameHeight <= 0) {
            return new Dimension(maxWidth, maxHeight);
        }
        double scale = Math.min(maxWidth / (double) frameWidth,
                maxHeight / (double) frameHeight);
        return new Dimension(
                Math.max(1, (int) Math.round(frameWidth * scale)),
                Math.max(1, (int) Math.round(frameHeight * scale)));
    }

    static Rectangle fitBounds(int frameWidth, int frameHeight, int surfaceWidth, int surfaceHeight) {
        if (frameWidth <= 0 || frameHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return new Rectangle();
        }
        Dimension fitted = fit(frameWidth, frameHeight, surfaceWidth, surfaceHeight);
        return new Rectangle((surfaceWidth - fitted.width) / 2,
                (surfaceHeight - fitted.height) / 2, fitted.width, fitted.height);
    }
}
