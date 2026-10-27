package com.jian.scrcpy.sdl;

import com.jian.scrcpy.media.VideoHardwareDeviceProvider;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVHWDeviceContext;
import org.bytedeco.javacpp.Pointer;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Creates an FFmpeg D3D11VA device context from SDL's renderer-owned
 * ID3D11Device*. The device is AddRef'ed before FFmpeg takes ownership.
 *
 * This class deliberately stays in scrcpy-sdl: the core module must not depend
 * on SDL or Windows COM.
 */
final class SdlD3d11HardwareDeviceProvider implements VideoHardwareDeviceProvider, AutoCloseable {
    private final MemorySegment d3d11Device;
    private final Linker linker = Linker.nativeLinker();
    private final Arena arena = Arena.ofShared();
    private final MethodHandle addRef;
    private boolean closed;

    SdlD3d11HardwareDeviceProvider(MemorySegment d3d11Device) {
        if (d3d11Device == null || d3d11Device.address() == 0) {
            throw new IllegalArgumentException("ID3D11Device is null");
        }
        this.d3d11Device = d3d11Device;
        MemorySegment vtable = d3d11Device.reinterpret(8).get(ValueLayout.ADDRESS, 0)
                .reinterpret(8 * 3L);
        MemorySegment addRefAddress = vtable.get(ValueLayout.ADDRESS, 8);
        this.addRef = linker.downcallHandle(
                addRefAddress,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    @Override
    public AVBufferRef createDeviceContext(int ffmpegDeviceType) {
        if (closed || ffmpegDeviceType != AV_HWDEVICE_TYPE_D3D11VA) {
            return null;
        }

        AVBufferRef ref = av_hwdevice_ctx_alloc(ffmpegDeviceType);
        if (ref == null || ref.isNull()) {
            return null;
        }

        AVHWDeviceContext context = new AVHWDeviceContext(ref.data());
        Pointer hwctx = context.hwctx();
        if (hwctx == null || hwctx.isNull()) {
            av_buffer_unref(ref);
            return null;
        }

        // AVD3D11VADeviceContext::device is the first field of hwctx.
        // FFmpeg releases this interface when the AVHWDeviceContext dies.
        int addResult = (int) addRef.invokeExact(d3d11Device);
        if (addResult == 0) {
            av_buffer_unref(ref);
            return null;
        }

        MemorySegment hwctxMemory = MemorySegment.ofAddress(hwctx.address()).reinterpret(
                ValueLayout.ADDRESS.byteSize());
        hwctxMemory.set(ValueLayout.ADDRESS, 0, d3d11Device);

        int initResult = av_hwdevice_ctx_init(ref);
        if (initResult < 0) {
            av_buffer_unref(ref);
            return null;
        }

        return ref;
    }

    @Override
    public void close() {
        closed = true;
        arena.close();
    }
}
