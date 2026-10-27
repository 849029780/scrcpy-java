package com.jian.scrcpy.media;

import org.bytedeco.ffmpeg.avutil.AVBufferRef;

/**
 * Supplies an FFmpeg hardware device context owned by the caller.
 *
 * A renderer backend may use this hook to make FFmpeg decode on the same
 * physical/native GPU device used for presentation. Returning {@code null}
 * means that the decoder should create its normal platform-default device.
 */
@FunctionalInterface
public interface VideoHardwareDeviceProvider {
    AVBufferRef createDeviceContext(int ffmpegDeviceType);

    VideoHardwareDeviceProvider DEFAULT = ignored -> null;
}
