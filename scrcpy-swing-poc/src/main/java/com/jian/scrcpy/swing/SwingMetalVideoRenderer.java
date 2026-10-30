package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX;

/** macOS VideoToolbox CVPixelBuffer -> CoreImage/Metal -> CAMetalLayer. */
public final class SwingMetalVideoRenderer implements SwingHardwareVideoRenderer {
    private final SwingVideoSurface surface;
    private final NativeMetalCanvas canvas;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SwingMetalVideoRenderer(SwingVideoSurface surface) {
        this.surface = surface;
        this.canvas = new NativeMetalCanvas();
        surface.installNativeCanvas(canvas);
    }

    @Override public String backend() { return "videotoolbox"; }

    @Override public AVBufferRef createDeviceContext(int type) {
        // VideoToolbox frames are CVPixelBufferRef objects and do not need a
        // renderer-owned FFmpeg device. FFmpeg creates its normal VT context.
        return null;
    }

    @Override public boolean renderHardware(HardwareVideoFrame hardwareFrame) {
        if (closed.get() || !"videotoolbox".equalsIgnoreCase(hardwareFrame.backend())) return false;
        AVFrame frame = hardwareFrame.frame();
        BytePointer pixelBuffer = frame.data(3);
        if (pixelBuffer == null || pixelBuffer.isNull()) return false;
        try {
            if (!canvas.render(pixelBuffer.address(), frame.width(), frame.height())) return false;
            surface.showHardwareFrame(frame.width(), frame.height());
            hardwareFrame.close();
            return true;
        } catch (Throwable t) {
            System.err.println("[scrcpy-swing] Metal presentation failed; using CPU fallback: " + t);
            return false;
        }
    }

    @Override public void renderSoftware(VideoFrame frame) { surface.accept(frame); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) canvas.close();
    }
}
