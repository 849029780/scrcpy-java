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
    private static final class NativePointer extends Pointer {
        NativePointer(long address) {
            this.address = address;
        }
    }

    private final SwingVideoSurface surface;
    private final NativeD3D11Canvas canvas;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean firstNativePresentLogged = new AtomicBoolean();

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
        if (closed.get() || !"d3d11va".equalsIgnoreCase(hardwareFrame.backend())) {
            return false;
        }

        AVFrame frame = hardwareFrame.frame();
        BytePointer texture = frame.data(0);
        BytePointer arrayIndex = frame.data(1);
        if (texture == null || texture.isNull()) {
            return false;
        }

        boolean rendered;
        try {
            rendered = canvas.renderHardwareFrame(
                    texture.address(),
                    arrayIndex == null || arrayIndex.isNull() ? 0 : arrayIndex.address(),
                    frame.width(),
                    frame.height(),
                    frame.colorspace(),
                    frame.color_range());
        } catch (Throwable error) {
            System.err.println("[scrcpy-swing][" + hardwareFrame.serial()
                    + "] D3D11 native texture presentation threw; decoder will attempt CPU readback: " + error);
            return false;
        }

        if (!rendered) {
            return false;
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
        // Native rendering is synchronous through VideoProcessorBlt/Present.
        // Release the retained AVFrame only after the GPU command has been queued.
        hardwareFrame.close();
        return true;
    }

    @Override
    public void renderSoftware(VideoFrame frame) {
        surface.accept(frame);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            canvas.close();
        }
    }
}
