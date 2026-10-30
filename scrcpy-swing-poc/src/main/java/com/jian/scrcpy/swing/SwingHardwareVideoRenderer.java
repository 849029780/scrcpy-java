package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.VideoHardwareDeviceProvider;
import com.jian.scrcpy.media.VideoRenderer;

/** Platform hardware renderer used by the Swing frontend. */
public interface SwingHardwareVideoRenderer extends VideoRenderer, VideoHardwareDeviceProvider, AutoCloseable {
    String backend();
}
