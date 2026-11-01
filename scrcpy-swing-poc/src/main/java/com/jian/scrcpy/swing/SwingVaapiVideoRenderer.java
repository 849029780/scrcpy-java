package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.javacpp.*;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avutil.*;

/** Bounded asynchronous VAAPI decode-surface -> X11 presentation. */
public final class SwingVaapiVideoRenderer implements SwingHardwareVideoRenderer {
    private static final class NativePointer extends Pointer {
        NativePointer(long address) { this.address = address; }
    }

    private final SwingVideoSurface surface;
    private final NativeVaapiCanvas canvas;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor presenter = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "scrcpy-vaapi-present");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public SwingVaapiVideoRenderer(SwingVideoSurface surface) {
        this.surface = surface;
        this.canvas = new NativeVaapiCanvas();
        surface.installNativeCanvas(canvas);
    }

    @Override public String backend() { return "vaapi"; }

    @Override public AVBufferRef createDeviceContext(int type) {
        if (closed.get() || type != AV_HWDEVICE_TYPE_VAAPI) return null;
        long display = canvas.retainVaDisplay();
        AVBufferRef ref = av_hwdevice_ctx_alloc(type);
        if (ref == null || ref.isNull()) return null;
        try {
            AVHWDeviceContext ctx = new AVHWDeviceContext(ref.data());
            // AVVAAPIDeviceContext.display is the first public field in hwctx.
            try (PointerPointer fields = new PointerPointer(ctx.hwctx())) {
                fields.put(0, new NativePointer(display));
            }
            int result = av_hwdevice_ctx_init(ref);
            if (result < 0) {
                av_buffer_unref(ref);
                System.err.printf("[scrcpy-swing] av_hwdevice_ctx_init(VAAPI) failed: %d%n", result);
                return null;
            }
            System.out.println("[scrcpy-swing] FFmpeg VAAPI shares the Swing renderer's VADisplay");
            return ref;
        } catch (Throwable t) {
            av_buffer_unref(ref);
            System.err.println("[scrcpy-swing] cannot initialize shared VAAPI device: " + t);
            return null;
        }
    }

    @Override public boolean renderHardware(HardwareVideoFrame hardwareFrame) {
        if (closed.get() || !"vaapi".equalsIgnoreCase(hardwareFrame.backend())) return false;
        AVFrame frame = hardwareFrame.frame();
        BytePointer vaSurface = frame.data(3);
        if (vaSurface == null || vaSurface.isNull()) return false;
        try {
            presenter.execute(() -> {
                try {
                    if (canvas.render(vaSurface.address(), frame.width(), frame.height())) {
                        surface.showHardwareFrame(frame.width(), frame.height());
                    }
                } catch (Throwable t) {
                    System.err.println("[scrcpy-swing] VAAPI presentation failed; dropping hardware frame: " + t);
                } finally {
                    // vaSyncSurface in the native presenter completes before
                    // this reference is released, so FFmpeg may safely recycle it.
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
