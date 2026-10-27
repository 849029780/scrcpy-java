package com.jian.scrcpy.sdl;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import com.jian.scrcpy.media.VideoRenderer;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final int QUEUE_SIZE = 2;

    private final Sdl3 sdl;
    private final MemorySegment window;
    private final MemorySegment renderer;
    private final SdlD3d11HardwareDeviceProvider deviceProvider;
    private final AVBufferRef ffmpegDevice;
    private final SdlD3d11Interop interop;
    private final BlockingQueue<HardwareVideoFrame> pending = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    private SdlD3d11VideoRenderer(String title, int width, int height) {
        sdl = new Sdl3();
        if (!sdl.initVideo()) {
            sdl.close();
            throw new IllegalStateException("SDL_Init(SDL_INIT_VIDEO) failed: " + sdl.error());
        }

        MemorySegment createdWindow = sdl.createWindow(title, width, height);
        if (createdWindow.address() == 0) {
            sdl.close();
            throw new IllegalStateException("SDL_CreateWindow failed: " + sdl.error());
        }
        window = createdWindow;

        MemorySegment createdRenderer = sdl.createRenderer(window, "direct3d11");
        if (createdRenderer.address() == 0) {
            sdl.destroyWindow(window);
            sdl.close();
            throw new IllegalStateException("SDL_CreateRenderer(direct3d11) failed: " + sdl.error());
        }
        renderer = createdRenderer;

        MemorySegment device = sdl.rendererD3d11Device(renderer);
        if (device.address() == 0) {
            sdl.destroyRenderer(renderer);
            sdl.destroyWindow(window);
            sdl.close();
            throw new IllegalStateException("SDL renderer did not expose ID3D11Device*");
        }

        deviceProvider = new SdlD3d11HardwareDeviceProvider(device);
        AVBufferRef createdDevice = deviceProvider.createDeviceContext(AV_HWDEVICE_TYPE_D3D11VA);
        if (createdDevice == null || createdDevice.isNull()) {
            deviceProvider.close();
            sdl.destroyRenderer(renderer);
            sdl.destroyWindow(window);
            sdl.close();
            throw new IllegalStateException("FFmpeg D3D11VA device initialization failed");
        }
        ffmpegDevice = createdDevice;

        MemorySegment deviceContext = deviceProvider.deviceContext(ffmpegDevice);
        if (deviceContext.address() == 0) {
            av_buffer_unref(ffmpegDevice);
            deviceProvider.close();
            sdl.destroyRenderer(renderer);
            sdl.destroyWindow(window);
            sdl.close();
            throw new IllegalStateException("FFmpeg D3D11VA device has no immediate context");
        }

        interop = new SdlD3d11Interop(sdl, renderer, deviceContext, width, height);
    }

    public static SdlD3d11VideoRenderer create(String title, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid video size");
        return new SdlD3d11VideoRenderer(title, width, height);
    }

    /**
     * Supplies a renderer-owned D3D11 device context to FfmpegVideoDecoder.
     * The decoder therefore allocates D3D11VA surfaces on the same GPU device
     * used by this SDL renderer.
     */
    public com.jian.scrcpy.media.VideoHardwareDeviceProvider hardwareDeviceProvider() {
        return deviceProvider;
    }

    @Override
    public boolean renderHardware(HardwareVideoFrame frame) {
        if (closed.get()) return false;
        if (!pending.offer(frame)) {
            HardwareVideoFrame old = pending.poll();
            if (old != null) old.close();
            if (!pending.offer(frame)) return false;
        }
        return true;
    }

    @Override
    public void renderSoftware(VideoFrame frame) {
        // This backend is intentionally hardware-only. The core decoder falls
        // back to its normal JavaFX/software sink when this renderer is absent.
        frame.close();
    }

    /**
     * Render at most one queued frame. Must run on the SDL video thread.
     */
    public boolean pump() {
        if (closed.get()) return false;
        HardwareVideoFrame frame = pending.poll();
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
        return window;
    }

    public MemorySegment renderer() {
        return renderer;
    }

    /**
     * Run the SDL render loop on the thread which created the SDL video
     * subsystem. SDL documents renderer creation/presentation as main-thread
     * operations, so native teardown is also performed here.
     */
    public void pumpLoop() {
        if (closed.get()) return;
        try {
            while (!closeRequested.get()) {
                pump();
                Thread.sleep(4);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }

    public void requestClose() {
        closeRequested.set(true);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HardwareVideoFrame frame;
        while ((frame = pending.poll()) != null) frame.close();
        interop.destroy(sdl);
        av_buffer_unref(ffmpegDevice);
        deviceProvider.close();
        sdl.destroyRenderer(renderer);
        sdl.destroyWindow(window);
        sdl.close();
    }

}
