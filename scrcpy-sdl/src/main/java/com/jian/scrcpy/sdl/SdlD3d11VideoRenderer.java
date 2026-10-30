package com.jian.scrcpy.sdl;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import com.jian.scrcpy.media.VideoRenderer;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;

import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA;
import static org.bytedeco.ffmpeg.global.avutil.av_buffer_unref;

/**
 * Experimental SDL3/Direct3D11 hardware-frame presentation backend.
 *
 * <p>SDL video APIs are process-global and must be serialized on one SDL
 * video thread. All device windows/renderers are therefore owned by the
 * shared Runtime below, while each device retains its own D3D11 renderer,
 * FFmpeg device context, interop resources and latest-frame mailbox.</p>
 */
public final class SdlD3d11VideoRenderer implements VideoRenderer, AutoCloseable {
    private final String title;
    private final int width;
    private final int height;
    private final CompletableFuture<Void> initialized = new CompletableFuture<>();
    private final AtomicReference<HardwareVideoFrame> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    private volatile Sdl3 sdl;
    private volatile MemorySegment window = MemorySegment.NULL;
    private volatile MemorySegment renderer = MemorySegment.NULL;
    private volatile SdlD3d11HardwareDeviceProvider deviceProvider;
    private volatile AVBufferRef ffmpegDevice;
    private volatile SdlD3d11Interop interop;

    private SdlD3d11VideoRenderer(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
    }

    /**
     * Creates a per-device SDL window on the single shared SDL video thread.
     * The returned decoder provider is backed by this window's D3D11 device.
     */
    public static SdlD3d11VideoRenderer create(String title, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid video size");
        SdlD3d11VideoRenderer result = new SdlD3d11VideoRenderer(title, width, height);
        try {
            Runtime.call(() -> {
                result.initialize(Runtime.sdl());
                Runtime.renderers.add(result);
                return null;
            });
            result.initialized.complete(null);
            return result;
        } catch (RuntimeException e) {
            result.initialized.completeExceptionally(e);
            result.closeRequested.set(true);
            Runtime.execute(() -> {
                Runtime.renderers.remove(result);
                result.destroyOnOwnerThread();
            });
            throw e;
        }
    }

    /**
     * Runs only on the shared SDL owner thread. The shared SDL subsystem is
     * initialized exactly once by Runtime; this method creates device-local
     * resources only.
     */
    private void initialize(Sdl3 localSdl) {
        sdl = localSdl;
        try {
            window = localSdl.createWindow(title, width, height);
            if (window.address() == 0) {
                throw new IllegalStateException("SDL_CreateWindow failed: " + localSdl.error());
            }

            renderer = localSdl.createRenderer(window, "direct3d11");
            if (renderer.address() == 0) {
                throw new IllegalStateException("SDL_CreateRenderer(direct3d11) failed: " + localSdl.error());
            }

            MemorySegment device = localSdl.rendererD3d11Device(renderer);
            if (device.address() == 0) {
                throw new IllegalStateException("SDL renderer did not expose ID3D11Device*");
            }

            deviceProvider = new SdlD3d11HardwareDeviceProvider(device);
            ffmpegDevice = deviceProvider.createDeviceContext(AV_HWDEVICE_TYPE_D3D11VA);
            if (ffmpegDevice == null || ffmpegDevice.isNull()) {
                throw new IllegalStateException("FFmpeg D3D11VA device initialization failed");
            }

            MemorySegment deviceContext = deviceProvider.deviceContext(ffmpegDevice);
            if (deviceContext.address() == 0) {
                throw new IllegalStateException("FFmpeg D3D11VA device has no immediate context");
            }

            interop = new SdlD3d11Interop(localSdl, renderer, deviceContext, width, height);
        } catch (Throwable t) {
            destroyOnOwnerThread();
            if (t instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Unable to initialize SDL D3D11 renderer", t);
        }
    }

    public com.jian.scrcpy.media.VideoHardwareDeviceProvider hardwareDeviceProvider() {
        awaitInitialized();
        return deviceProvider;
    }

    @Override
    public boolean renderHardware(HardwareVideoFrame frame) {
        if (closed.get() || closeRequested.get()) {
            frame.close();
            return false;
        }
        HardwareVideoFrame old = pending.getAndSet(frame);
        if (old != null) old.close();
        Runtime.wake();
        return true;
    }

    @Override
    public void renderSoftware(VideoFrame frame) {
        frame.close();
    }

    /** Must be called by Runtime on the shared SDL owner thread. */
    private boolean pump() {
        if (closed.get() || closeRequested.get()) return false;
        HardwareVideoFrame frame = pending.getAndSet(null);
        if (frame == null) return false;

        try {
            if (!"d3d11va".equalsIgnoreCase(frame.backend())) return false;
            MemorySegment sourceTexture = MemorySegment.ofAddress(frame.frame().data(0).address());
            long sourceArraySlice = frame.frame().data(1).address();

            interop.ensureSize(sdl, frame.width(), frame.height());
            interop.copyFrame(sdl, sourceTexture, sourceArraySlice);
            if (!sdl.renderTexture(renderer, interop.texture())) {
                throw new IllegalStateException("SDL_RenderTexture failed: " + sdl.error());
            }
            if (!sdl.renderPresent(renderer)) {
                throw new IllegalStateException("SDL_RenderPresent failed: " + sdl.error());
            }
            return true;
        } finally {
            frame.close();
        }
    }

    public MemorySegment window() {
        awaitInitialized();
        return window;
    }

    public MemorySegment renderer() {
        awaitInitialized();
        return renderer;
    }

    public void requestClose() {
        if (!closeRequested.compareAndSet(false, true)) return;
        Runtime.execute(() -> {
            Runtime.renderers.remove(this);
            destroyOnOwnerThread();
        });
        Runtime.wake();
    }

    @Override
    public void close() {
        requestClose();
    }

    private void awaitInitialized() {
        initialized.join();
    }

    /** Releases this device's resources. Must run on the shared SDL thread. */
    private void destroyOnOwnerThread() {
        if (!closed.compareAndSet(false, true)) return;
        HardwareVideoFrame frame = pending.getAndSet(null);
        if (frame != null) frame.close();

        Sdl3 localSdl = sdl;
        SdlD3d11Interop localInterop = interop;
        AVBufferRef localFfmpegDevice = ffmpegDevice;
        SdlD3d11HardwareDeviceProvider localProvider = deviceProvider;
        MemorySegment localRenderer = renderer;
        MemorySegment localWindow = window;

        interop = null;
        ffmpegDevice = null;
        deviceProvider = null;
        renderer = MemorySegment.NULL;
        window = MemorySegment.NULL;
        sdl = null;

        if (localInterop != null && localSdl != null) localInterop.destroy(localSdl);
        if (localFfmpegDevice != null) av_buffer_unref(localFfmpegDevice);
        if (localProvider != null) localProvider.close();
        if (localSdl != null) {
            if (localRenderer != null && localRenderer.address() != 0) localSdl.destroyRenderer(localRenderer);
            if (localWindow != null && localWindow.address() != 0) localSdl.destroyWindow(localWindow);
        }
    }

    /**
     * SDL3 has one process-wide video subsystem and a main-thread video API.
     * Keep one DLL mapping, one SDL_Init call and one owner thread for every
     * connected device. Device creation/destruction is serialized through
     * commands; frame presentation is multiplexed on the same thread.
     */
    private static final class Runtime {
        private static final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
        private static final Set<SdlD3d11VideoRenderer> renderers =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private static final CompletableFuture<Sdl3> ready = new CompletableFuture<>();
        private static volatile Thread ownerThread;

        private static Sdl3 sdl() {
            return ready.join();
        }

        private static synchronized void startIfNeeded() {
            if (ownerThread != null) return;
            ownerThread = Thread.ofPlatform()
                    .daemon()
                    .name("scrcpy-sdl-video")
                    .start(Runtime::run);
        }

        private static void run() {
            Sdl3 runtimeSdl = null;
            try {
                runtimeSdl = new Sdl3();
                if (!runtimeSdl.initVideo()) {
                    String error = runtimeSdl.error();
                    runtimeSdl.close();
                    runtimeSdl = null;
                    throw new IllegalStateException("SDL_Init(SDL_INIT_VIDEO) failed: " + error);
                }
                ready.complete(runtimeSdl);

                while (true) {
                    Runnable command = commands.poll(4, TimeUnit.MILLISECONDS);
                    if (command != null) {
                        try {
                            command.run();
                        } catch (Throwable t) {
                            System.err.println("[scrcpy-sdl] SDL owner command failed:");
                            t.printStackTrace(System.err);
                        }
                    }

                    for (SdlD3d11VideoRenderer videoRenderer : renderers) {
                        try {
                            videoRenderer.pump();
                        } catch (Throwable t) {
                            System.err.printf("[scrcpy-sdl] renderer failed (%s): %s%n",
                                    videoRenderer.title, t);
                            t.printStackTrace(System.err);
                            renderers.remove(videoRenderer);
                            videoRenderer.closeRequested.set(true);
                            videoRenderer.destroyOnOwnerThread();
                        }
                    }
                }
            } catch (Throwable t) {
                ready.completeExceptionally(t);
                System.err.println("[scrcpy-sdl] SDL video runtime stopped:");
                t.printStackTrace(System.err);
            }
        }

        private static <T> T call(java.util.concurrent.Callable<T> action) {
            startIfNeeded();
            ready.join();
            CompletableFuture<T> result = new CompletableFuture<>();
            commands.add(() -> {
                try {
                    result.complete(action.call());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            wake();
            return result.join();
        }

        private static void execute(Runnable action) {
            startIfNeeded();
            if (ready.isCompletedExceptionally()) return;
            if (!ready.isDone()) {
                ready.whenComplete((ignored, error) -> {
                    if (error == null) {
                        commands.add(action);
                        wake();
                    }
                });
                return;
            }
            commands.add(action);
            wake();
        }

        private static void wake() {
            Thread thread = ownerThread;
            if (thread != null) LockSupport.unpark(thread);
        }
    }
}
