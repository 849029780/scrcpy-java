package com.jian.scrcpy.sdl;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

final class SdlD3d11Interop {
    private static final int COPY_SUBRESOURCE_REGION_VTABLE_INDEX = 46;
    private final MemorySegment renderer;
    private final MemorySegment deviceContext;
    private final Linker linker = Linker.nativeLinker();
    private final MethodHandle copySubresourceRegion;
    private MemorySegment destinationTexture;
    private int width;
    private int height;

    SdlD3d11Interop(Sdl3 sdl, MemorySegment renderer, MemorySegment deviceContext, int width, int height) {
        if (deviceContext == null || deviceContext.address() == 0)
            throw new IllegalArgumentException("ID3D11DeviceContext is null");
        this.renderer = renderer;
        this.deviceContext = deviceContext;
        MemorySegment vtable = deviceContext.reinterpret(8).get(ADDRESS, 0)
                .reinterpret((COPY_SUBRESOURCE_REGION_VTABLE_INDEX + 1L) * ADDRESS.byteSize());
        MemorySegment address = vtable.get(ADDRESS,
                (long) COPY_SUBRESOURCE_REGION_VTABLE_INDEX * ADDRESS.byteSize());
        copySubresourceRegion = linker.downcallHandle(address, FunctionDescriptor.ofVoid(
                ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.width = width;
        this.height = height;
        destinationTexture = sdl.createNv12Texture(renderer, width, height);
        if (destinationTexture.address() == 0)
            throw new IllegalStateException("SDL_CreateTexture(NV12) failed: " + sdl.error());
        if (sdl.textureD3d11Texture(destinationTexture).address() == 0) {
            sdl.destroyTexture(destinationTexture);
            throw new IllegalStateException("SDL NV12 texture has no D3D11 native texture");
        }
    }

    void ensureSize(Sdl3 sdl, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid texture size");
        if (this.width == width && this.height == height && destinationTexture.address() != 0) return;
        sdl.destroyTexture(destinationTexture);
        destinationTexture = sdl.createNv12Texture(renderer, width, height);
        if (destinationTexture.address() == 0) {
            throw new IllegalStateException("SDL_CreateTexture(NV12) failed: " + sdl.error());
        }
        if (sdl.textureD3d11Texture(destinationTexture).address() == 0) {
            sdl.destroyTexture(destinationTexture);
            destinationTexture = MemorySegment.NULL;
            throw new IllegalStateException("SDL NV12 texture has no D3D11 native texture");
        }
        this.width = width;
        this.height = height;
    }

    void copyFrame(Sdl3 sdl, MemorySegment sourceTexture, long sourceArraySlice) {
        MemorySegment destination = sdl.textureD3d11Texture(destinationTexture);
        if (sourceTexture == null || sourceTexture.address() == 0)
            throw new IllegalArgumentException("source ID3D11Texture2D is null");
        if (!sdl.flushRenderer(renderer))
            throw new IllegalStateException("SDL_FlushRenderer failed: " + sdl.error());
        try {
            copySubresourceRegion.invokeExact(deviceContext, destination, 0, 0, 0, 0,
                    sourceTexture, (int) sourceArraySlice, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new IllegalStateException("D3D11 CopySubresourceRegion failed", t);
        }
    }

    MemorySegment texture() { return destinationTexture; }
    void destroy(Sdl3 sdl) {
        if (destinationTexture != null && destinationTexture.address() != 0) {
            sdl.destroyTexture(destinationTexture);
            destinationTexture = MemorySegment.NULL;
        }
    }
}