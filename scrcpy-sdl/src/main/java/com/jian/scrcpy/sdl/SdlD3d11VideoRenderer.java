package com.jian.scrcpy.sdl;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import com.jian.scrcpy.media.VideoRenderer;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA;
import static org.bytedeco.ffmpeg.global.avutil.av_buffer_unref;

/**
 * Experimental SDL3/Direct3D11 hardware-frame presentation backend.
 *
 * <p>FFmpeg D3D11 frames stay on the GPU. The decoder hands an owned
 * {@link HardwareVideoFrame} to this renderer; {@link #pump()} then copies the
 * underlying ID3D11Texture2D directly into an SDL-owned NV12 texture and
 * presents it. No av_hwframe_transfer_data() or swscale() is used.</p>
 *
 * <p>SDL video/render calls must run on the SDL thread, so renderHardware()
 * only queues frames. Call pump() from the SDL event/render loop.</p>
 */
public final class SdlD3d11VideoRenderer implements VideoRenderer, AutoCloseable {
    private final String title;
    private final int width;
    private final int height;
    private final java.util.concurrent.CompletableFuture<Void> initialized =
            new java.util.concurrent.CompletableFuture<>();
    // Latest-frame mailbox: keeping more than one decoded GPU surface only
    // adds presentation latency for a live mirror. Replaced frames are released
    // immediately without GPU->CPU transfer or GPU copy.
    private final AtomicReference<HardwareVideoFrame> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    private volatile Sdl3 sdl;
    private volatile MemorySegment window = MemorySegment.NULL;
    private volatile MemorySegment renderer = MemorySegment.NULL;
    private volatile SdlD3d11HardwareDeviceProvider deviceProvider;
    private volatile AVBufferRef ffmpegDevice;
    private volatile SdlD3d11Interop interop;
    private volatile Thread renderThread;

    private SdlD3d11VideoRenderer(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
    }

    /**
     * Creates the renderer and starts its SDL owner thread. SDL video,
     * renderer, D3D11 interop and FFmpeg device initialization all happen on
     * that same platform thread. The method returns only after the shared
     * D3D11 device provider is ready for the decoder.
     */
    public static SdlD3d11VideoRenderer create(String title, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid video size");
        SdlD3d11VideoRenderer result = new SdlD3d11VideoRenderer(title, width, height);
        result.renderThread = Thread.ofPlatform()
                .name("scrcpy-sdl")
                .start(result::pumpLoop);
        try {
            result.initialized.join();
        } catch (java.util.concurrent.CompletionException e) {
            result.requestClose();
            throw e;
        }
        return result;
    }

    private void initialize() {
        Sdl3 localSdl = new Sdl3();
        if (!localSdl.initVideo()) {
            String error = localSdl.error();
            localSdl.close();
            throw new IllegalStateException("SDL_Init(SDL_INIT_VIDEO) failed: " + error);
        }

        MemorySegment createdWindow = localSdl.createWindow(title, width, height);
        if (createdWindow.address() == 0) {
            String error = localSdl.error();
            localSdl.close();
            throw new IllegalStateException("SDL_CreateWindow failed: " + error);
        }

        MemorySegment createdRenderer = localSdl.createRenderer(createdWindow, "direct3d11");
        if (createdRenderer.address() == 0) {
            String error = localSdl.error();
            localSdl.destroyWindow(createdWindow);
            localSdl.close();
            throw new IllegalStateException("SDL_CreateRenderer(direct3d11) failed: " + error);
        }

        MemorySegment device = localSdl.rendererD3d11Device(createdRenderer);
        if (device.address() == 0) {
            localSdl.destroyRenderer(createdRenderer);
            localSdl.destroyWindow(createdWindow);
            localSdl.close();
            throw new IllegalStateException("SDL renderer did not expose ID3D11Device*");
        }

        SdlD3d11HardwareDeviceProvider localProvider = new SdlD3d11HardwareDeviceProvider(device);
        AVBufferRef localFfmpegDevice =
                localProvider.createDeviceContext(AV_HWDEVICE_TYPE_D3D11VA);
        if (localFfmpegDevice == null || localFfmpegDevice.isNull()) {
            localProvider.close();
            localSdl.destroyRenderer(createdRenderer);
            localSdl.destroyWindow(createdWindow);
            localSdl.close();
            throw new IllegalStateException("FFmpeg D3D11VA device initialization failed");
        }

        MemorySegment deviceContext = localProvider.deviceContext(localFfmpegDevice);
        if (deviceContext.address() == 0) {
            av_buffer_unref(localFfmpegDevice);
            localProvider.close();
            localSdl.destroyRenderer(createdRenderer);
            localSdl.destroyWindow(createdWindow);
            localSdl.close();
            throw new IllegalStateException("FFmpeg D3D11VA device has no immediate context");
        }

        SdlD3d11Interop localInterop;
        try {
            localInterop = new SdlD3d11Interop(
                    localSdl, createdRenderer, deviceContext, width, height);
        } catch (Throwable t) {
            av_buffer_unref(localFfmpegDevice);
            localProvider.close();
            localSdl.destroyRenderer(createdRenderer);
            localSdl.destroyWindow(createdWindow);
            localSdl.close();
            throw t;
        }

        sdl = localSdl;
        window = createdWindow;
        renderer = createdRenderer;
        deviceProvider = localProvider;
        ffmpegDevice = localFfmpegDevice;
        interop = localInterop;
    }

    /**
     * Supplies the renderer-owned D3D11 device to FfmpegVideoDecoder.
     * The decoder therefore allocates D3D11VA surfaces on the same GPU device
     * used by this SDL renderer.
     */
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
        Thread thread = renderThread;
        if (thread != null) LockSupport.unpark(thread);
        return true;
    }

    @Override
    public void renderSoftware(VideoFrame frame) {
        frame.close();
    }

    /**
     * Render at most one queued frame. Must run on the SDL owner thread.
     */
    public boolean pump() {
        if (closed.get()) return false;
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

    /**
     * Owns the SDL video subsystem for the lifetime of this renderer.
     * Creation and destruction therefore happen on the same platform thread
     * that performs rendering.
     */
    public void pumpLoop() {
        try {
            initialize();
            initialized.complete(null);
            while (!closeRequested.get()) {
                if (!pump()) {
                    // renderHardware() unparks immediately when a new GPU frame
                    // arrives. The timeout only prevents a lost wakeup from
                    // stalling the owner thread indefinitely.
                    LockSupport.parkNanos(4_000_000L);
                }
            }
        } catch (Throwable t) {
            if (!initialized.isDone()) initialized.completeExceptionally(t);
            else t.printStackTrace(System.err);
        } finally {
            destroyOnOwnerThread();
        }
    }

    public void requestClose() {
        closeRequested.set(true);
        Thread thread = renderThread;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void close() {
        requestClose();
    }

    private void awaitInitialized() {
        try {
            initialized.join();
        } catch (java.util.concurrent.CompletionException e) {
            throw e;
        }
    }

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

        if (localInterop != null && localSdl != null) localInterop.destroy(localSdl);
        if (localFfmpegDevice != null) av_buffer_unref(localFfmpegDevice);
        if (localProvider != null) localProvider.close();
        if (localSdl != null) {
            if (localRenderer != null && localRenderer.address() != 0) localSdl.destroyRenderer(localRenderer);
            if (localWindow != null && localWindow.address() != 0) localSdl.destroyWindow(localWindow);
            localSdl.close();
        }
    }
}
