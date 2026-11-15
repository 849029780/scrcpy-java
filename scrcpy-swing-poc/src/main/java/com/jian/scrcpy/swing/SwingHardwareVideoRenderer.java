package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.VideoHardwareDeviceProvider;
import com.jian.scrcpy.media.VideoRenderer;

/** Platform hardware renderer used by the Swing frontend. */
public interface SwingHardwareVideoRenderer extends VideoRenderer, VideoHardwareDeviceProvider, AutoCloseable {
    String backend();

    /**
     * Pause or resume GPU presentation rendering (e.g. when minimized or hidden).
     * When suspended, hardware frames are immediately closed/released without
     * submitting to the GPU pipeline, eliminating VRAM copies and 3D engine load.
     */
    default void setSuspended(boolean suspended) {}

    @Override
    void close();
}
