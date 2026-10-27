package com.jian.scrcpy.media;

import org.bytedeco.ffmpeg.avutil.AVFrame;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_ref;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;

/**
 * Ownership-safe reference to an FFmpeg hardware frame.
 *
 * <p>The decoder reuses its receive frame immediately, so a renderer must not
 * retain that object directly. This wrapper owns a separate AVFrame reference;
 * the underlying AVBufferRefs keep the GPU resource alive until close().</p>
 */
public final class HardwareVideoFrame implements AutoCloseable {
    private final AVFrame frame;
    private final String serial;
    private final String backend;
    private final long pts;
    private final AtomicBoolean closed = new AtomicBoolean();

    private HardwareVideoFrame(AVFrame frame, String serial, String backend, long pts) {
        this.frame = frame;
        this.serial = serial;
        this.backend = backend;
        this.pts = pts;
    }

    static HardwareVideoFrame from(AVFrame source, String serial, String backend, long pts) {
        AVFrame retained = av_frame_alloc();
        if (retained == null || retained.isNull()) return null;
        if (av_frame_ref(retained, source) < 0) {
            av_frame_free(retained);
            return null;
        }
        return new HardwareVideoFrame(
                retained,
                Objects.requireNonNullElse(serial, "unknown"),
                Objects.requireNonNullElse(backend, "unknown"),
                pts);
    }

    public AVFrame frame() {
        if (closed.get()) throw new IllegalStateException("hardware frame is closed");
        return frame;
    }

    public String serial() { return serial; }
    public String backend() { return backend; }
    public int width() { return frame.width(); }
    public int height() { return frame.height(); }
    public int pixelFormat() { return frame.format(); }
    public long pts() { return pts; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            av_frame_unref(frame);
            av_frame_free(frame);
        }
    }
}
