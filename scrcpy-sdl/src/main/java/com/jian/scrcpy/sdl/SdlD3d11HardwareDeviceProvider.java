package com.jian.scrcpy.sdl;

import com.jian.scrcpy.media.VideoHardwareDeviceProvider;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVHWDeviceContext;
import org.bytedeco.javacpp.Pointer;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA;

final class SdlD3d11HardwareDeviceProvider implements VideoHardwareDeviceProvider, AutoCloseable {
    private final MemorySegment d3d11Device;
    private final Linker linker = Linker.nativeLinker();
    private final Arena arena = Arena.ofShared();
    private final MethodHandle addRef;
    private boolean closed;

    SdlD3d11HardwareDeviceProvider(MemorySegment d3d11Device) {
        if (d3d11Device == null || d3d11Device.address() == 0)
            throw new IllegalArgumentException("ID3D11Device is null");
        this.d3d11Device = d3d11Device;
        MemorySegment vtable = d3d11Device.reinterpret(8).get(ValueLayout.ADDRESS, 0).reinterpret(24);
        this.addRef = linker.downcallHandle(vtable.get(ValueLayout.ADDRESS, 8),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    @Override public AVBufferRef createDeviceContext(int ffmpegDeviceType) {
        if (closed || ffmpegDeviceType != AV_HWDEVICE_TYPE_D3D11VA) return null;
        AVBufferRef ref = av_hwdevice_ctx_alloc(ffmpegDeviceType);
        if (ref == null || ref.isNull()) return null;
        AVHWDeviceContext context = new AVHWDeviceContext(ref.data());
        Pointer hwctx = context.hwctx();
        if (hwctx == null || hwctx.isNull()) { av_buffer_unref(ref); return null; }
        try {
            if ((int) addRef.invokeExact(d3d11Device) == 0) {
                av_buffer_unref(ref); return null;
            }
        } catch (Throwable e) {
            av_buffer_unref(ref);
            throw new IllegalStateException("Failed to AddRef ID3D11Device", e);
        }
        MemorySegment.ofAddress(hwctx.address()).reinterpret(ValueLayout.ADDRESS.byteSize())
                .set(ValueLayout.ADDRESS, 0, d3d11Device);
        if (av_hwdevice_ctx_init(ref) < 0) {
            av_buffer_unref(ref);
            return null;
        }
        return ref;
    }

    MemorySegment deviceContext(AVBufferRef ref) {
        if (ref == null || ref.isNull()) return MemorySegment.NULL;
        Pointer hwctx = new AVHWDeviceContext(ref.data()).hwctx();
        if (hwctx == null || hwctx.isNull()) return MemorySegment.NULL;
        return MemorySegment.ofAddress(hwctx.address()).reinterpret(16)
                .get(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteSize());
    }

    @Override public void close() { closed = true; arena.close(); }
}