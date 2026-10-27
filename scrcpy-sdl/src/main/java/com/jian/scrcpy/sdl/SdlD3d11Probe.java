package com.jian.scrcpy.sdl;

import java.lang.foreign.MemorySegment;

/**
 * First SDL3 milestone: prove that Java 25 can create an SDL renderer and
 * obtain the native ID3D11Device* that will later be shared with FFmpeg.
 *
 * Runtime:
 *   java --enable-native-access=ALL-UNNAMED
 *        -Dscrcpy.sdl3=C:\\path\\to\\SDL3.dll
 *        ... com.jian.scrcpy.sdl.SdlD3d11Probe
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

            MemorySegment renderer = MemorySegment.NULL;
            try {
                String requested = isWindows() ? "direct3d11" : null;
                renderer = sdl.createRenderer(window, requested);
                if (renderer.address() == 0) {
                    throw new IllegalStateException("SDL_CreateRenderer failed: " + sdl.error());
                }

                String rendererName = sdl.rendererName(renderer);
                System.out.println("[scrcpy-java][sdl] renderer: " + rendererName);

                if (isWindows()) {
                    MemorySegment device = sdl.rendererD3d11Device(renderer);
                    if (device.address() == 0) {
                        throw new IllegalStateException(
                                "SDL direct3d11 renderer did not expose ID3D11Device*: " + sdl.error());
                    }
                    System.out.printf("[scrcpy-java][sdl] ID3D11Device*: 0x%x%n", device.address());
                    System.out.println("[scrcpy-java][sdl] D3D11 interop probe passed");
                } else {
                    System.out.println("[scrcpy-java][sdl] SDL3 renderer probe passed; platform GPU interop follows later");
                }
            } finally {
                sdl.destroyRenderer(renderer);
                sdl.destroyWindow(window);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
