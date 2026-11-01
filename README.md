# scrcpy-java 0.2.0

Java 25 desktop reimplementation targeting official scrcpy-server **5.0.1**.

Stack: Maven + Netty 4.2.x + JavaFX + FFmpeg/JavaCPP.

## Desktop support

- Windows
- macOS
- Linux
- System-wide ADB installation is optional.
- Self-contained desktop packages include Android platform-tools, scrcpy-server 5.0.1 and a Java runtime image.
- ADB resolution order: `-Dscrcpy.adb`, bundled platform-tools, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then `PATH`.
- Source builds still work with an existing Android SDK/ADB installation.

The `Desktop Packages` GitHub Actions workflow builds independent Windows, macOS and Linux app images. It can be run manually and also runs for version tags.

## 0.2.0

- scrcpy 5.0.1 media framing and codec IDs
- Netty video/audio/control channels
- H.264/H.265/AV1 video options with FFmpeg decoding
- Opus/AAC/FLAC audio decoding and desktop playback
- multi-device JavaFX workspace and synchronized primary-device controls
- touch and UHID mouse/keyboard control
- bidirectional clipboard synchronization
- file transfer and APK installation
- device checkbox selection
- automatic reconnect after an unexpected disconnect (3 second interval, maximum 20 connection attempts)
- bundled/cross-platform ADB discovery
- scrcpy-server download/cache plus bundled-server discovery

This remains an incremental Java port of the scrcpy desktop client; platform-specific runtime behavior is continuously verified on Windows, macOS and Linux CI.
## Bundled scrcpy server

Place the official scrcpy 5.0.1 server artifact at the repository root as `scrcpy-server-v5.0.1.jar`. The application verifies its SHA-256 before pushing it to Android devices and no longer downloads the server automatically. For packaged builds, include the same file at `scrcpy-core/src/main/resources/scrcpy-server-v5.0.1.jar`.

Official artifact: https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1  
SHA-256: `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`
