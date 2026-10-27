package com.jian.scrcpy.media;

/**
 * Rendering boundary between FFmpeg and the presentation layer.
 *
 * <p>Software frames are delivered as BGRA buffers. Hardware frames retain
 * FFmpeg's native hardware surface (for example a D3D11 texture) and transfer
 * ownership to the renderer only when {@link #renderHardware(HardwareVideoFrame)}
 * returns {@code true}. This keeps SDL/JavaFX/web presentation code out of the
 * decoder and allows a renderer to implement a real zero-copy path.</p>
 */
public interface VideoRenderer {
    boolean renderHardware(HardwareVideoFrame frame);

    /**
     * Consumes a software BGRA frame. The renderer must eventually close it
     * after it no longer needs its backing buffer.
     */
    void renderSoftware(VideoFrame frame);
}
