package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX;

/** Bounded asynchronous VideoToolbox CVPixelBuffer -> Metal presentation. */
public final class SwingMetalVideoRenderer implements SwingHardwareVideoRenderer {
    private final SwingVideoSurface surface;
    private final NativeMetalCanvas canvas;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor presenter = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "scrcpy-metal-present");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public SwingMetalVideoRenderer(SwingVideoSurface surface) {
        this.surface = surface;
        this.canvas = new NativeMetalCanvas();
        surface.installNativeCanvas(canvas);
    }

    @Override public String backend() { return "videotoolbox"; }

    @Override public AVBufferRef createDeviceContext(int type) {
        // VideoToolbox frames are CVPixelBufferRef objects and use FFmpeg's VT context.
        return null;
    }

    @Override public boolean renderHardware(HardwareVideoFrame hardwareFrame) {
        if (closed.get() || !"videotoolbox".equalsIgnoreCase(hardwareFrame.backend())) return false;
        AVFrame frame = hardwareFrame.frame();
        BytePointer pixelBuffer = frame.data(3);
        if (pixelBuffer == null || pixelBuffer.isNull()) return false;
        try {
            presenter.execute(() -> {
                try {
                    if (canvas.render(pixelBuffer.address(), frame.width(), frame.height())) {
                        surface.showHardwareFrame(frame.width(), frame.height());
                    }
                } catch (Throwable t) {
                    System.err.println("[scrcpy-swing] Metal presentation failed; dropping hardware frame: " + t);
                } finally {
                    hardwareFrame.close();
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            return false;
        }
    }

    @Override public void renderSoftware(VideoFrame frame) { surface.accept(frame); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        presenter.shutdown();
        boolean interrupted = false;
        for (;;) {
            try {
                if (presenter.awaitTermination(1, TimeUnit.SECONDS)) break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        canvas.close();
        if (interrupted) Thread.currentThread().interrupt();
    }
}
