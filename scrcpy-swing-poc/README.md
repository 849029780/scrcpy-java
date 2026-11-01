# scrcpy-java Swing frontend

This module contains an independent Swing desktop client. The existing
`scrcpy-app` JavaFX application is intentionally unchanged and remains
available as a separate frontend.

## Features

- ADB device discovery, selection, connect-all, disconnect and reconnect.
- Per-connection scrcpy settings, including video/audio/control, codec,
  encoder, hardware/software decoder, bitrate, max size/FPS, crop and start app.
- Simultaneous multi-device video tiles with a larger primary device.
- Primary-device input broadcasting, optional coordinate adaptation, touch
  mode and UHID relative-mouse mode (Ctrl+Esc releases mouse capture).
- Per-device navigation actions: Back, Home, Recents, notification panel,
  volume, power, display on/off and stay-awake.
- Bidirectional desktop/device clipboard synchronization.
- Batch file transfer and APK installation with per-device progress.
- FFmpeg video/audio decoding through the existing `scrcpy-core` module.

## Rendering

On Windows, the Swing frontend now has a native D3D11 presentation path:

`FFmpeg D3D11VA decode -> retained AVFrame/D3D11 texture -> D3D11 Video Processor -> AWT Canvas swap chain`

The native Canvas creates the D3D11 device and supplies an AddRef'd reference to
FFmpeg's `AVHWDeviceContext`. This keeps decoding and presentation on the same
D3D11 device. The decoded texture is passed to the D3D11 video processor directly;
the normal hardware presentation path does not call `av_hwframe_transfer_data`,
`sws_scale`, or create a `BufferedImage`.

The existing BGRA/Java2D renderer remains the fallback when the native library,
D3D11 video processor, or a compatible hardware frame is unavailable. Non-Windows
platforms continue to use the portable renderer. The native backend is Windows-only.

### Build the native renderer (Windows)

Requires CMake 3.24+, a Visual Studio C++ workload, Windows SDK, and JDK 25.
From the repository root in PowerShell:

```powershell
cmake -S scrcpy-swing-poc/native -B scrcpy-swing-poc/native/build -A x64
cmake --build scrcpy-swing-poc/native/build --config Release
$env:PATH = "$PWD\scrcpy-swing-poc\native\build\Release;$env:PATH"
mvn -pl scrcpy-swing-poc -am install
mvn -pl scrcpy-swing-poc exec:java
```

If CMake cannot locate JNI/JAWT, set `JAVA_HOME` to the JDK 25 installation
before configuring. The JavaFX `scrcpy-app` module is not changed.

`D3D11VA -> D3D11 Video Processor -> SwapChain` avoids a CPU frame download;
the GPU may still perform an internal GPU-side video-processing pass to convert
NV12/P010 into the swap-chain format. This is zero-copy with respect to the
CPU/system-memory frame path, not a claim that no GPU resource or processing
operation occurs.

## Build and run

Requires JDK 25 and the Android platform-tools executable (bundled, SDK, PATH,
or `-Dscrcpy.adb=...`).

From the repository root:

```powershell
mvn -pl scrcpy-swing-poc -am install
mvn -pl scrcpy-swing-poc exec:java
```

The JavaFX application remains independently runnable:

```powershell
mvn -pl scrcpy-app javafx:run
```

To use a specific ADB executable:

```powershell
mvn -pl scrcpy-swing-poc exec:java -Dscrcpy.adb=C:\\Android\\platform-tools\\adb.exe
```
