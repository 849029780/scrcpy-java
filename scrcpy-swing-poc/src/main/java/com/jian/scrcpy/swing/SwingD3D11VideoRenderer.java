package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import com.jian.scrcpy.media.VideoHardwareDeviceProvider;
import com.jian.scrcpy.media.VideoRenderer;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVHWDeviceContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * D3D11VA -> D3D11 Video Processor -> Swing/AWT Canvas renderer.
 *
 * The native Canvas creates the D3D11 device. FFmpeg receives an AVHWDeviceContext
 * wrapping an AddRef'd reference to that exact device, so decoded D3D11 textures
 * can be consumed by the same device without downloading them to system memory.
 */
public final class SwingD3D11VideoRenderer implements SwingHardwareVideoRenderer {
    // Keep GPU completion waits off FFmpeg decoder threads. Workers are shared
    // across devices instead of allocating one waiting thread per phone.
    private static final ExecutorService PRESENT_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            task -> {
                Thread thread = new Thread(task, "scrcpy-d3d11-present");
                thread.setDaemon(true);
                return thread;
            });
    private static final int MAX_QUEUED_HARDWARE_FRAMES = 2;
    private static final class NativePointer extends Pointer {
        NativePointer(long address) {
            this.address = address;
        }
    }

    private final SwingVideoSurface surface;
    private final NativeD3D11Canvas canvas;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean firstNativePresentLogged = new AtomicBoolean();
    private final ArrayBlockingQueue<HardwareVideoFrame> pendingFrames =
            new ArrayBlockingQueue<>(MAX_QUEUED_HARDWARE_FRAMES);
    private final Object drainMonitor = new Object();
    private boolean drainScheduled;

    public SwingD3D11VideoRenderer(SwingVideoSurface surface) {
        this.surface = surface;
        this.canvas = new NativeD3D11Canvas();
        surface.installNativeCanvas(canvas);
    }

    @Override
    public String backend() { return "d3d11va"; }

    @Override
    public AVBufferRef createDeviceContext(int ffmpegDeviceType) {
        if (closed.get() || ffmpegDeviceType != AV_HWDEVICE_TYPE_D3D11VA) {
            return null;
        }

        long devicePointer;
        try {
            devicePointer = canvas.retainD3D11Device();
        } catch (Throwable error) {
            System.err.println("[scrcpy-swing] D3D11 device is unavailable; using FFmpeg's default device: " + error);
            return null;
        }
        AVBufferRef reference = av_hwdevice_ctx_alloc(ffmpegDeviceType);
        if (reference == null || reference.isNull()) {
            canvas.releaseD3D11Device(devicePointer);
            System.err.println("[scrcpy-swing] av_hwdevice_ctx_alloc(D3D11VA) failed");
            return null;
        }

        try {
            // AVD3D11VADeviceContext.device is the first field in hwctx.
            // The FFmpeg 8.1.2 public header documents this field as the
            // required caller-supplied ID3D11Device pointer.
            AVHWDeviceContext deviceContext = new AVHWDeviceContext(reference.data());
            try (PointerPointer fields = new PointerPointer(deviceContext.hwctx())) {
                fields.put(0, new NativePointer(devicePointer));
            }

            int result = av_hwdevice_ctx_init(reference);
            if (result < 0) {
                av_buffer_unref(reference);
                System.err.printf("[scrcpy-swing] av_hwdevice_ctx_init(D3D11VA) failed: %d%n", result);
                return null;
            }
            System.out.println("[scrcpy-swing] FFmpeg D3D11VA shares the Swing renderer's D3D11 device");
            return reference;
        } catch (Throwable error) {
            av_buffer_unref(reference);
            System.err.println("[scrcpy-swing] cannot initialize shared D3D11VA device: " + error);
            return null;
        }
    }

    @Override
    public boolean renderHardware(HardwareVideoFrame hardwareFrame) {
        if (closed.get() || !"d3d11va".equalsIgnoreCase(hardwareFrame.backend())) return false;

        // Transfer ownership only after enqueue succeeds. A full bounded queue
        // applies backpressure to this decoder rather than dropping a retained
        // frame or forcing a GPU-to-CPU readback.
        try {
            while (!closed.get()) {
                if (pendingFrames.offer(hardwareFrame, 50, TimeUnit.MILLISECONDS)) {
                    schedulePresent();
                    return true;
                }
            }
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void schedulePresent() {
        synchronized (drainMonitor) {
            if (drainScheduled || closed.get()) return;
            drainScheduled = true;
        }
        PRESENT_EXECUTOR.execute(this::presentOne);
    }

    private void presentOne() {
        HardwareVideoFrame hardwareFrame = pendingFrames.poll();
        try {
            if (hardwareFrame != null && !closed.get()) presentHardwareFrame(hardwareFrame);
        } catch (Throwable error) {
            System.err.println("[scrcpy-swing] asynchronous D3D11 presentation failed: " + error);
        } finally {
            if (hardwareFrame != null) hardwareFrame.close();
        }

        boolean reschedule;
        synchronized (drainMonitor) {
            reschedule = !closed.get() && !pendingFrames.isEmpty();
            if (!reschedule) {
                drainScheduled = false;
                drainMonitor.notifyAll();
            }
        }
        if (reschedule) PRESENT_EXECUTOR.execute(this::presentOne);
    }

    private void presentHardwareFrame(HardwareVideoFrame hardwareFrame) {
        AVFrame frame = hardwareFrame.frame();
        BytePointer texture = frame.data(0);
        BytePointer arrayIndex = frame.data(1);
        if (texture == null || texture.isNull()) return;

        boolean rendered = canvas.renderHardwareFrame(
                texture.address(),
                arrayIndex == null || arrayIndex.isNull() ? 0 : arrayIndex.address(),
                frame.width(), frame.height(), frame.colorspace(), frame.color_range());
        if (!rendered) {
            System.err.printf("[scrcpy-swing][%s] D3D11 presentation rejected a retained frame; dropping without CPU readback%n",
                    hardwareFrame.serial());
            return;
        }

        if (firstNativePresentLogged.compareAndSet(false, true)) {
            System.out.printf("[scrcpy-swing][%s] D3D11 direct-texture presentation confirmed: "
                            + "texture=0x%x arraySlice=%d size=%dx%d; VideoProcessorBlt + GPU completion + Present succeeded; "
                            + "no CPU frame transfer on this path%n",
                    hardwareFrame.serial(), texture.address(),
                    arrayIndex == null || arrayIndex.isNull() ? 0 : arrayIndex.address(),
                    frame.width(), frame.height());
        }
        surface.showHardwareFrame(frame.width(), frame.height());
    }
    @Override
    public void renderSoftware(VideoFrame frame) {
        surface.accept(frame);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HardwareVideoFrame pending;
        while ((pending = pendingFrames.poll()) != null) pending.close();
        synchronized (drainMonitor) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (drainScheduled) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    System.err.println("[scrcpy-swing] timed out waiting for D3D11 presentation worker during close");
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(drainMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        canvas.close();
    }
}
