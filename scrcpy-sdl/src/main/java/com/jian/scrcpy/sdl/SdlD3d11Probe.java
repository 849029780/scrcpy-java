package com.jian.scrcpy.sdl;

import java.lang.foreign.MemorySegment;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA;
import static org.bytedeco.ffmpeg.global.avutil.av_buffer_unref;

public final class SdlD3d11Probe {
    private static final int WIDTH = 960, HEIGHT = 540;
    private SdlD3d11Probe() {}

    public static void main(String[] args) {
        try (Sdl3 sdl = new Sdl3()) {
            if (!sdl.initVideo()) throw new IllegalStateException("SDL_Init failed: " + sdl.error());
            MemorySegment window = sdl.createWindow("scrcpy-java SDL3 D3D11 interop", WIDTH, HEIGHT);
            if (window.address() == 0) throw new IllegalStateException("SDL_CreateWindow failed: " + sdl.error());
            SdlRendererSelector.Selection selection = null;
            try {
                selection = SdlRendererSelector.create(sdl, window);
                System.out.printf("[scrcpy-java][sdl] renderer=%s interop=%s%n",
                        selection.rendererName(), selection.interop());
                if (selection.interop() != SdlRendererSelector.Interop.D3D11 ||
                        selection.device().address() == 0) {
                    System.out.println("[scrcpy-java][sdl] D3D11 unavailable; probe skipped");
                    return;
                }
                try (SdlD3d11HardwareDeviceProvider provider =
                             new SdlD3d11HardwareDeviceProvider(selection.device())) {
                    AVBufferRef device = provider.createDeviceContext(AV_HWDEVICE_TYPE_D3D11VA);
                    if (device == null || device.isNull())
                        throw new IllegalStateException("FFmpeg D3D11VA device initialization failed");
                    try {
                        MemorySegment context = provider.deviceContext(device);
                        if (context.address() == 0) throw new IllegalStateException("No D3D11 device context");
                        MemorySegment source = sdl.createNv12Texture(selection.renderer(), WIDTH, HEIGHT);
                        if (source.address() == 0) throw new IllegalStateException("SDL source texture failed: " + sdl.error());
                        try {
                            MemorySegment sourceNative = sdl.textureD3d11Texture(source);
                            SdlD3d11Interop interop = new SdlD3d11Interop(
                                    sdl, selection.renderer(), context, WIDTH, HEIGHT);
                            try {
                                interop.copyFrame(sdl, sourceNative, 0);
                                if (!sdl.renderTexture(selection.renderer(), interop.texture()))
                                    throw new IllegalStateException("SDL_RenderTexture failed: " + sdl.error());
                                if (!sdl.renderPresent(selection.renderer()))
                                    throw new IllegalStateException("SDL_RenderPresent failed: " + sdl.error());
                                System.out.println("[scrcpy-java][sdl] D3D11 GPU copy/present path passed");
                            } finally { interop.destroy(sdl); }
                        } finally { sdl.destroyTexture(source); }
                    } finally { av_buffer_unref(device); }
                }
            } finally {
                if (selection != null) sdl.destroyRenderer(selection.renderer());
                sdl.destroyWindow(window);
            }
        }
    }
}