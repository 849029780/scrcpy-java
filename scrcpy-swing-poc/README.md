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

## Rendering note

The current Swing video surface is a portable BGRA/Java2D implementation. It
keeps the newest frame and reuses its image storage, but it is not GPU
zero-copy. The existing native D3D11 Canvas proof of concept remains in this
module as a separate experiment and is not yet connected to FFmpeg hardware
frames. Vulkan/D3D11 texture interop is intentionally not claimed by this
frontend.

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
