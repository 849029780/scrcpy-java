# scrcpy-sdl

Experimental SDL3 rendering backend for scrcpy-java.

This module intentionally does not replace the JavaFX application yet. The
first milestone proves the critical Windows interop needed to follow scrcpy
5.0.1's rendering architecture:

1. Java 25 calls SDL3 through the standard Foreign Function & Memory API.
2. SDL creates a `direct3d11` renderer.
3. Java reads `SDL.renderer.d3d11.device` from the renderer.
4. The returned `ID3D11Device*` will be injected into FFmpeg's
   `AVHWDeviceContext` in the next milestone.
5. The existing JavaFX/PixelBuffer renderer remains the stable fallback until
   the SDL hardware path is complete.

## Windows probe

Put SDL3.dll somewhere on disk, then run the probe with native access enabled
and point `scrcpy.sdl3` at it.

The expected success output includes:

    [scrcpy-java][sdl] renderer: direct3d11
    [scrcpy-java][sdl] ID3D11Device*: 0x...
    [scrcpy-java][sdl] D3D11 interop probe passed

No SDL3 native binary is committed to this repository.
