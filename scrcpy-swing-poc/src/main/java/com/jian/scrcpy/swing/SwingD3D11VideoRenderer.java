package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVHWDeviceContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * D3D11VA -> asynchronous D3D11 Video Processor -> Swing/AWT Canvas renderer.
 *
 * FFmpeg and the native renderer share one D3D11 device. Each submitted frame
 * remains referenced by HardwareVideoFrame until its native event query signals
 * GPU completion. Native output uses independent bounded frame slots, so the
 * decoder thread never waits for GPU completion.
 */
public final class SwingD3D11VideoRenderer implements SwingHardwareVideoRenderer {
    private static final ScheduledExecutorService PRESENT_EXECUTOR =
            Executors.newScheduledThreadPool(
                    Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
                    task -> {
                        Thread thread = new Thread(task, "scrcpy-d3d11-present");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final int MAX_QUEUED_HARDWARE_FRAMES = 2;

    private static final class NativePointer extends Pointer {
        NativePointer(long address) { this.address = address; }
    }

    private static final class PendingFrame {
        final HardwareVideoFrame frame;
        final int width;
        final int height;
        final long texture;
        final long arraySlice;
        volatile long submittedAtNanos;

        PendingFrame(HardwareVideoFrame frame, AVFrame avFrame) {
            this.frame = frame;
            this.width = avFrame.width();
            this.height = avFrame.height();
            BytePointer texturePointer = avFrame.data(0);
            BytePointer arrayPointer = avFrame.data(1);
            this.texture = texturePointer == null || texturePointer.isNull()
                    ? 0 : texturePointer.address();
            this.arraySlice = arrayPointer == null || arrayPointer.isNull()
                    ? 0 : arrayPointer.address();
        }
    }

    private final SwingVideoSurface surface;
    private final NativeD3D11Canvas canvas;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean firstNativePresentLogged = new AtomicBoolean();
    private final ArrayBlockingQueue<HardwareVideoFrame> pendingFrames =
            new ArrayBlockingQueue<>(MAX_QUEUED_HARDWARE_FRAMES);
    // Accessed only by the single scheduled pump for this renderer.
    private final Map<Long, PendingFrame> inFlight = new HashMap<>();
    private final Object drainMonitor = new Object();
    private boolean pumpScheduled;

    // Per-renderer rolling telemetry. Presented is counted only after native
    // polling returns a successful Present result, never when merely queued.
    private final AtomicLong statsSubmitted = new AtomicLong();
    private final AtomicLong statsPresented = new AtomicLong();
    private final AtomicLong statsPresentFailures = new AtomicLong();
    private final AtomicLong statsBackpressure = new AtomicLong();
    private final AtomicLong statsSubmitNanos = new AtomicLong();
    private final AtomicLong statsSubmitToPresentNanos = new AtomicLong();
    private final AtomicLong statsMaxSubmitToPresentNanos = new AtomicLong();
    private final AtomicLong statsMaxInFlight = new AtomicLong();
    private long statsLastReportNanos = System.nanoTime();

    public SwingD3D11VideoRenderer(SwingVideoSurface surface) {
        this.surface = surface;
        this.canvas = new NativeD3D11Canvas();
        surface.installNativeCanvas(canvas);
    }

    @Override
    public String backend() { return "d3d11va"; }

    @Override
    public AVBufferRef createDeviceContext(int ffmpegDeviceType) {
        if (closed.get() || ffmpegDeviceType != AV_HWDEVICE_TYPE_D3D11VA) return null;

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
        try {
            while (!closed.get()) {
                if (pendingFrames.offer(hardwareFrame, 50, TimeUnit.MILLISECONDS)) {
                    // close() may race with the successful offer. If shutdown
                    // already started, remove our frame and leave ownership
                    // with the caller, which will close it.
                    if (closed.get()) {
                        pendingFrames.remove(hardwareFrame);
                        return false;
                    }
                    schedulePump();
                    return true;
                }
            }
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void schedulePump() {
        synchronized (drainMonitor) {
            if (pumpScheduled) return;
            pumpScheduled = true;
        }
        PRESENT_EXECUTOR.execute(this::pump);
    }

    /**
     * Submit as many frames as the native three-slot ring accepts, then poll
     * completion queries. A token is retired only after native GPU completion.
     */
    private void pump() {
        try {
            for (;;) {
                boolean progressed = false;

                long completedToken;
                while ((completedToken = canvas.pollCompletedFrame()) != 0) {
                    long token = Math.abs(completedToken);
                    PendingFrame completed = inFlight.remove(token);
                    if (completed != null) {
                        if (completedToken > 0) {
                            onPresented(completed);
                        } else {
                            statsPresentFailures.incrementAndGet();
                            System.err.printf("[scrcpy-swing][%s] D3D11 GPU work completed but Present failed; frame dropped%n",
                                    completed.frame.serial());
                        }
                        completed.frame.close();
                    }
                    progressed = true;
                }

                if (!closed.get()) {
                    HardwareVideoFrame queued = pendingFrames.peek();
                    if (queued != null) {
                        AVFrame avFrame = queued.frame();
                        PendingFrame candidate = new PendingFrame(queued, avFrame);
                        long submitStarted = System.nanoTime();
                        long token = candidate.texture == 0 ? -1 : canvas.submitHardwareFrame(
                                candidate.texture, candidate.arraySlice,
                                candidate.width, candidate.height,
                                avFrame.colorspace(), avFrame.color_range());
                        long submitElapsed = System.nanoTime() - submitStarted;
                        if (token > 0) {
                            candidate.submittedAtNanos = System.nanoTime();
                            statsSubmitted.incrementAndGet();
                            statsSubmitNanos.addAndGet(submitElapsed);
                            pendingFrames.poll();
                            inFlight.put(token, candidate);
                            updateMaximum(statsMaxInFlight, inFlight.size());
                            progressed = true;
                        } else if (token == 0) {
                            statsBackpressure.incrementAndGet();
                        } else if (token < 0) {
                            pendingFrames.poll();
                            queued.close();
                            System.err.printf("[scrcpy-swing][%s] D3D11 rejected frame submission; dropping without CPU readback%n",
                                    queued.serial());
                            progressed = true;
                        }
                    }
                }

                if (!progressed) break;
            }
            reportPerformanceStats();
        } catch (Throwable error) {
            System.err.println("[scrcpy-swing] asynchronous D3D11 presentation pump failed: " + error);
            error.printStackTrace(System.err);
        } finally {
            synchronized (drainMonitor) {
                pumpScheduled = false;
                boolean again = !inFlight.isEmpty() || (!closed.get() && !pendingFrames.isEmpty());
                if (again) {
                    pumpScheduled = true;
                    PRESENT_EXECUTOR.schedule(this::pump, 1, TimeUnit.MILLISECONDS);
                } else {
                    drainMonitor.notifyAll();
                }
            }
        }
    }

    private void onPresented(PendingFrame completed) {
        long latency = Math.max(0L, System.nanoTime() - completed.submittedAtNanos);
        statsPresented.incrementAndGet();
        statsSubmitToPresentNanos.addAndGet(latency);
        updateMaximum(statsMaxSubmitToPresentNanos, latency);
        if (firstNativePresentLogged.compareAndSet(false, true)) {
            System.out.printf("[scrcpy-swing][%s] D3D11 asynchronous presentation confirmed: "
                            + "texture=0x%x arraySlice=%d size=%dx%d; GPU query completed before Present; "
                            + "no CPU frame transfer on this path%n",
                    completed.frame.serial(), completed.texture, completed.arraySlice,
                    completed.width, completed.height);
        }
        surface.showHardwareFrame(completed.width, completed.height);
    }

    private void reportPerformanceStats() {
        long now = System.nanoTime();
        long elapsed = now - statsLastReportNanos;
        if (elapsed < TimeUnit.SECONDS.toNanos(5)) return;
        statsLastReportNanos = now;

        long submitted = statsSubmitted.getAndSet(0);
        long presented = statsPresented.getAndSet(0);
        long failures = statsPresentFailures.getAndSet(0);
        long backpressure = statsBackpressure.getAndSet(0);
        long submitNanos = statsSubmitNanos.getAndSet(0);
        long latencyNanos = statsSubmitToPresentNanos.getAndSet(0);
        long maxLatencyNanos = statsMaxSubmitToPresentNanos.getAndSet(0);
        long maxInFlight = statsMaxInFlight.getAndSet(0);
        double seconds = elapsed / 1_000_000_000.0;
        double submitAvgMs = submitted == 0 ? 0.0 : submitNanos / 1_000_000.0 / submitted;
        double latencyAvgMs = presented == 0 ? 0.0 : latencyNanos / 1_000_000.0 / presented;

        System.out.printf(java.util.Locale.ROOT,
                "[scrcpy-swing][%s] D3D11 stats: gpuSubmitted=%.1f fps, presented=%.1f fps, "
                        + "presentFailed=%d, submitBackpressure=%d, submitAvg=%.3f ms, "
                        + "submitToPresentAvg=%.2f ms, submitToPresentMax=%.2f ms, "
                        + "queue=%d/%d, inFlight=%d/%d%n",
                surface.serial(), submitted / seconds, presented / seconds,
                failures, backpressure, submitAvgMs, latencyAvgMs,
                maxLatencyNanos / 1_000_000.0,
                pendingFrames.size(), MAX_QUEUED_HARDWARE_FRAMES, inFlight.size(), maxInFlight);
    }

    private static void updateMaximum(AtomicLong target, long value) {
        target.accumulateAndGet(value, Math::max);
    }

    @Override
    public void renderSoftware(VideoFrame frame) {
        surface.accept(frame);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        HardwareVideoFrame queued;
        while ((queued = pendingFrames.poll()) != null) queued.close();
        schedulePump();

        boolean interrupted = false;
        synchronized (drainMonitor) {
            while (pumpScheduled || !inFlight.isEmpty()) {
                try {
                    drainMonitor.wait(1_000);
                    if (!pumpScheduled && !inFlight.isEmpty()) {
                        // Keep polling until every submitted GPU operation has
                        // completed; destroying the D3D11 device earlier would
                        // invalidate FFmpeg-owned texture references.
                        schedulePump();
                    }
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        }
        canvas.close();
        if (interrupted) Thread.currentThread().interrupt();
    }
}
