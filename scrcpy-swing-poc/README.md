# Swing / JAWT / D3D11 proof of concept

This is an isolated Windows-only rendering experiment. It does not replace the
JavaFX application and does not modify the existing FFmpeg decoder.

## What it verifies

1. A heavyweight AWT `Canvas` is hosted inside a normal Swing `JFrame`.
2. JAWT obtains the Canvas' native `HWND` using the public AWT native interface.
3. A native D3D11 device creates a DXGI swap chain for that HWND.
4. The swap chain is resized with the Canvas and presents a GPU-cleared test color.

The video pixels are not painted by Java2D. The native D3D11 renderer owns the
Canvas swap chain.

## Build native DLL (Windows, JDK 25, Visual Studio C++ tools, CMake)

Open a Developer PowerShell with `JAVA_HOME` pointing to the JDK:

```powershell
cmake -S scrcpy-swing-poc/native -B scrcpy-swing-poc/native/build -A x64
cmake --build scrcpy-swing-poc/native/build --config Release
```

Copy `scrcpy_swing_d3d11.dll` from the build output to a directory on
`java.library.path`, or set:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Djava.library.path=$PWD/scrcpy-swing-poc/native/build/Release"
```

## Run

From the repository root:

```powershell
mvn -pl scrcpy-swing-poc -am package
mvn -pl scrcpy-swing-poc exec:java -Dexec.mainClass=com.jian.scrcpy.swing.SwingD3D11PoC
```

Alternatively, run `com.jian.scrcpy.swing.SwingD3D11PoC` from the IDE with the
native DLL directory in `java.library.path`.

Expected result: one Swing window with a dark blue test surface. Resize and
move the window; the D3D11 swap chain should continue presenting inside the
Canvas without creating another top-level window.

## Next integration step

After this PoC passes on the target Windows/JDK/GPU combination, connect the
existing `HardwareVideoFrame` to a D3D11 renderer. That requires sharing the
same `ID3D11Device` with FFmpeg's `AVHWDeviceContext`, obtaining the decoded
D3D11 texture from the FFmpeg frame, and sampling it in a shader. The current
PoC intentionally does not claim to implement that zero-CPU-copy video path.

## Current limitations

- Windows only.
- One test Canvas in this PoC; the design can create one swap chain per Canvas
  for multiple device tiles in the same Swing frame.
- The render callback runs on Swing's EDT for simplicity. A production renderer
  should move presentation to a dedicated render thread and synchronize
  resize/peer lifecycle.
- AWT peer recreation, monitor/DPI changes, device removal, and shutdown races
  still need dedicated validation.
