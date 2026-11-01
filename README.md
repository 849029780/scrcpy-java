# scrcpy-java

<p align="center">
  <strong>A Java desktop client for scrcpy with multi-device screen mirroring and synchronized group control.</strong>
</p>

<p align="center">
  <a href="https://github.com/849029780/scrcpy-java">GitHub</a> ·
  <a href="https://github.com/Genymobile/scrcpy">Based on scrcpy</a>
</p>

[English](#english) | [简体中文](#简体中文)

---

## English

### Overview

**scrcpy-java** is a Java desktop client built around the [scrcpy](https://github.com/Genymobile/scrcpy) Android screen mirroring and control protocol. It brings multiple Android devices into one desktop workspace and focuses on a capability that is especially useful for device farms, testing labs, content operations, and multi-device workflows: **control several connected devices together from one place**.

Unlike a workflow that opens and operates one phone at a time, scrcpy-java can display multiple device screens simultaneously. Select a primary device for a larger view, keep other devices visible in the workspace, and send supported actions from the primary view to the connected devices through synchronized group control.

> **Project status:** The active desktop direction is the Swing client in `scrcpy-swing-poc`. The project is under active development; behavior and platform support may vary by feature and environment.

### Highlights

- **Multi-device workspace** — display multiple connected Android devices at the same time in a single desktop window.
- **Synchronized group control** — operate from the primary device view and broadcast supported control actions to connected devices.
- **Primary device layout** — enlarge the primary device while keeping the remaining devices in a compact grid; switch the primary device as needed.
- **Embedded and floating views** — keep device screens embedded in the workspace or detach them into independent windows.
- **Per-device connection settings** — configure video-related connection options such as resolution, frame rate, bit rate, and encoder where supported by the current client.
- **Touch and mouse control** — interact with Android devices using touch-style control or UHID mouse/keyboard mode where supported.
- **Batch file transfer** — select multiple devices and send files to them, with per-device progress.
- **Batch APK installation** — install an APK on selected devices and track installation progress.
- **Clipboard synchronization** — exchange clipboard text between desktop and Android devices.
- **Audio and video** — receive scrcpy media streams and decode supported formats using FFmpeg.
- **ADB device management** — discover connected devices and connect/disconnect them from the workspace.

### Screenshots

The screenshots below are stored in the repository's [`assets/`](assets) directory.

#### Multi-device workspace

The workspace keeps multiple live device screens visible together, making it easier to monitor and operate a group of phones.

![Multi-device workspace](assets/多设备投屏列表.png)

#### Multi-device group control — embedded mode

Operate from the primary device while keeping the other device screens arranged in the same workspace.

![Group control embedded mode](assets/多设备群控嵌入模式.png)

#### Multi-device group control — floating windows

Detach device screens into independent desktop windows when a separate view is more convenient.

![Group control floating windows](assets/多设备群控独立窗口.png)

#### Device list and workspace controls

![Device list](assets/设备列表.png)

![Device list with toolbar hidden](assets/多设备列表隐藏工具栏.png)

![Device workspace with toolbar hidden](assets/多设备投屏列表-隐藏工具栏.png)

#### Batch operations

Send files to multiple selected devices and monitor progress.

![Batch file transfer](assets/批量传送文件.png)

Install an APK on multiple selected devices.

![Batch APK installation](assets/批量安装应用.png)

#### Connection configuration

![Device connection settings](assets/设备连接配置.png)

### Technology

| Area | Technology |
| --- | --- |
| Language | Java 25 |
| Build | Maven |
| Desktop UI | Java Swing (active client) |
| Device communication | ADB + scrcpy Server 5.0.1 |
| Network transport | Netty 4.2.x |
| Media decoding | FFmpeg 8.1.2 via JavaCPP |
| Windows rendering | Native D3D11 integration (where available) |

### Requirements

- JDK 25
- Maven 3.9+
- Android Debug Bridge (ADB)
- An Android device with USB debugging enabled, or a device reachable through ADB over TCP/IP
- The matching official **scrcpy Server 5.0.1** artifact

Make sure the device is authorized for ADB on the computer before starting the client.

### Getting started

#### 1. Get the source

```bash
git clone https://github.com/849029780/scrcpy-java.git
cd scrcpy-java
```

#### 2. Provide scrcpy Server

Download the official server artifact for scrcpy 5.0.1:

- [scrcpy-server-v5.0.1](https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1)

Place it at the repository root and name it:

```text
scrcpy-server-v5.0.1.jar
```

The application validates the server file using SHA-256 before sending it to the Android device.

SHA-256:

```text
764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536
```

You can also provide a custom path using the `scrcpy.server.jar` system property or the `SCRCPY_SERVER_JAR` environment variable.

#### 3. Connect an Android device

Enable **Developer options → USB debugging**, connect the device, and verify that ADB can see it:

```bash
adb devices
```

For a device connected over the network, first connect it with ADB:

```bash
adb connect <device-ip>:<port>
```

#### 4. Build and launch the Swing client

Build the Swing client and its required modules:

```bash
mvn -pl scrcpy-swing-poc -am package
```

Launch it with Maven:

```bash
mvn -pl scrcpy-swing-poc -am exec:java
```

The Swing entry point is `com.jian.scrcpy.swing.SwingScrcpyApp`.

> Native rendering support depends on the platform and the matching native components. The Windows D3D11 path requires its native renderer library to be available to the application.

### Repository modules

| Module | Responsibility |
| --- | --- |
| `scrcpy-core` | Shared scrcpy protocol, ADB integration, Netty transports, media pipeline, and device/session logic |
| `scrcpy-swing-poc` | Swing desktop client and multi-device workspace |
| `scrcpy-app` | Earlier JavaFX client kept in the repository during the UI transition |

### Notes

- This project is an independent Java implementation/client that communicates with the official scrcpy Server; it is not the official Genymobile scrcpy project.
- The current target server protocol is scrcpy **5.0.1**. Use the matching server artifact.
- Some features are platform-specific and are still being validated. Check the issue tracker before relying on a feature in a production device farm.
- The screenshots illustrate the current application UI; they do not imply that every platform has identical rendering or feature support.

### Acknowledgements

- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — Android screen mirroring, control, and server protocol.
- [Netty](https://netty.io/) — asynchronous network framework.
- [FFmpeg](https://ffmpeg.org/) — audio/video decoding and media processing.
- [JavaCPP](https://github.com/bytedeco/javacpp) — Java bindings for native libraries.

---

## 简体中文

### 项目简介

**scrcpy-java** 是一个基于 Java 开发的 Android 设备投屏与控制桌面客户端，通信协议兼容官方 [scrcpy](https://github.com/Genymobile/scrcpy) Server 5.0.1。

项目的核心目标不只是实现单台手机的桌面投屏，而是提供一个面向多台 Android 设备的统一工作台：**多设备同时投屏，并支持从主设备进行群控操作**。适用于多设备测试、设备集中管理、内容运营以及需要同时观察和操作多台手机的场景。

用户可以在同一个桌面窗口中同时查看多台设备，将其中一台设为主设备并放大显示；通过主设备执行支持的操作时，可同步分发到其他已连接设备。

> **项目状态：** 当前重点开发方向为 `scrcpy-swing-poc` 中的 Swing 客户端。项目仍在持续迭代，具体功能和平台支持情况以实际版本为准。

### 核心功能

- **多设备同时投屏：** 一个桌面工作区同时展示多台 Android 设备，不需要逐台切换窗口。
- **多设备群控：** 以主设备作为操作入口，将支持的控制操作同步发送到其他已连接设备。
- **主设备布局：** 主设备放大显示，其余设备以网格形式排列；可以切换主设备。
- **嵌入式与独立窗口：** 设备画面可以嵌入主工作区，也可以拆分为独立窗口。
- **连接参数配置：** 根据当前客户端支持情况配置分辨率、帧率、码率、视频编码器等参数。
- **触控与鼠标控制：** 支持触控式操作，以及设备和平台支持时的 UHID 鼠标/键盘模式。
- **批量文件传输：** 勾选多台设备后批量发送文件，并查看各设备传输进度。
- **批量安装 APK：** 对选中的多台设备执行 APK 安装，并查看安装进度。
- **剪贴板同步：** 支持桌面与 Android 设备之间的文本剪贴板交互。
- **音视频解码：** 接收 scrcpy 音视频数据流，并通过 FFmpeg 解码支持的格式。
- **ADB 设备管理：** 发现设备，并在工作区中连接或断开设备。

### 技术栈

- Java 25
- Maven
- Java Swing 桌面 UI
- ADB + scrcpy Server 5.0.1
- Netty 4.2.x 网络传输
- FFmpeg 8.1.2 / JavaCPP 音视频解码
- Windows 平台可用的原生 D3D11 渲染集成

### 快速开始

1. 安装 JDK 25、Maven 3.9+ 和 Android ADB。
2. 克隆项目：`git clone https://github.com/849029780/scrcpy-java.git`
3. 下载官方 [scrcpy-server-v5.0.1](https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1)，放在项目根目录，并命名为 `scrcpy-server-v5.0.1.jar`。
4. 开启 Android 设备的 USB 调试，并通过 `adb devices` 确认设备已授权。
5. 在项目根目录执行：

```bash
mvn -pl scrcpy-swing-poc -am package
mvn -pl scrcpy-swing-poc -am exec:java
```

如需指定 Server 文件路径，可使用 JVM 系统属性 `scrcpy.server.jar` 或环境变量 `SCRCPY_SERVER_JAR`。程序会校验 Server 文件的 SHA-256。

### 项目声明

本项目是独立的 Java 客户端实现，并非 Genymobile 官方 scrcpy 项目。项目使用官方 scrcpy Server 进行 Android 设备通信，感谢 scrcpy 及 Netty、FFmpeg、JavaCPP 等开源项目。

---

## License

Please refer to the repository's license and third-party notices before redistributing binaries or source code.
