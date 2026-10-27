package com.jian.scrcpy.sdl;

import java.lang.foreign.MemorySegment;

import org.bytedeco.ffmpeg.avutil.AVBufferRef;

import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA;

/**
 * Cross-platform SDL3 renderer/device probe.
 *
 * The important invariant is that the native device reported here is the
 * renderer-owned device. FFmpeg hardware decoding will be created from this
 * same device, avoiding accidental cross-GPU interop on hybrid-GPU systems.
 */
public final class SdlD3d11Probe {
    private SdlD3d11Probe() {}

    public static void main(String[] args) {
        try (Sdl3 sdl = new Sdl3()) {
            if (!sdl.initVideo()) {
                throw new IllegalStateException("SDL_Init failed: " + sdl.error());
            }

            MemorySegment window = sdl.createWindow("scrcpy-java SDL3 probe", 960, 540);
            if (window.address() == 0) {
                throw new IllegalStateException("SDL_CreateWindow failed: " + sdl.error());
            }

            SdlRendererSelector.Selection selection = null;
            try {
                selection = SdlRendererSelector.create(sdl, window);
                System.out.printf("[scrcpy-java][sdl] renderer=%s interop=%s%n",
                        selection.rendererName(), selection.interop());

                if (selection.device().address() != 0) {
                    System.out.printf("[scrcpy-java][sdl] renderer device: 0x%x%n",
                            selection.device().address());
                }
                if (selection.auxiliaryDevice().address() != 0) {
                    System.out.printf("[scrcpy-java][sdl] renderer auxiliary device: 0x%x%n",
                            selection.auxiliaryDevice().address());
                }

                if (selection.interop() == SdlRendererSelector.Interop.D3D11
                        && selection.device().address() != 0) {
                    try (SdlD3d11HardwareDeviceProvider provider =
                                 new SdlD3d11HardwareDeviceProvider(selection.device())) {
                        AVBufferRef ffmpegDevice =
                                provider.createDeviceContext(AV_HWDEVICE_TYPE_D3D11VA);
                        if (ffmpegDevice == null || ffmpegDevice.isNull()) {
                            throw new IllegalStateException(
                                    "FFmpeg D3D11VA device context initialization failed");
                        }
                        try {
                            System.out.println(
                                    "[scrcpy-java][sdl] FFmpeg D3D11VA device context initialized from SDL device");
                        } finally {
                            org.bytedeco.ffmpeg.global.avutil.av_buffer_unref(ffmpegDevice);
                        }
                    }
                }

                System.out.println("[scrcpy-java][sdl] renderer capability probe passed");
            } finally {
                if (selection != null) {
                    sdl.destroyRenderer(selection.renderer());
                }
                sdl.destroyWindow(window);
            }
        }
    }
}
