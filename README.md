# scrcpy-java 0.2.0

Java 25 desktop reimplementation targeting official scrcpy-server **5.0.1**.

Stack: Maven + Netty 4.2.x + JavaFX + FFmpeg/JavaCPP.

## 0.2.0
- scrcpy 5.0.1 media framing and codec IDs
- Netty video/audio/control channel foundation
- control/device protocol serialization
- ADB push/forward/server lifecycle
- audio stream handler boundary
- non-blocking encoded packet recorder queue
- clipboard sequence/ACK synchronization
- Android keycode foundation

This is an incremental source port. It is not yet 1:1 feature-complete with upstream scrcpy 5.0.1. FFmpeg MP4/MKV muxing, PCM audio output, complete keyboard mapping, UHID/gamepad/OTG and the remaining desktop features are still being ported.
