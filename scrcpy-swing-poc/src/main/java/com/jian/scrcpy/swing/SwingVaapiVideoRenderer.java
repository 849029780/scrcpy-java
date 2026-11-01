package com.jian.scrcpy.swing;

import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.VideoFrame;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.javacpp.*;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avutil.*;

/** Linux VAAPI decode surface -> X11 drawable, without a CPU frame download. */
public final class SwingVaapiVideoRenderer implements SwingHardwareVideoRenderer {
    private static final class NativePointer extends Pointer {
        NativePointer(long address) { this.address = address; }
    }

    private final SwingVideoSurface surface;
    private final NativeVaapiCanvas canvas;
    private final AtomicBoolean closed = new AtomicBoolean();

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
            if (!canvas.render(vaSurface.address(), frame.width(), frame.height())) return false;
            surface.showHardwareFrame(frame.width(), frame.height());
            hardwareFrame.close();
            return true;
        } catch (Throwable t) {
            System.err.println("[scrcpy-swing] VAAPI presentation failed; using CPU fallback: " + t);
            return false;
        }
    }

    @Override public void renderSoftware(VideoFrame frame) { surface.accept(frame); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) canvas.close();
    }
}
