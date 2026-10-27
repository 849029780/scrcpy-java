package com.jian.scrcpy.sdl;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

final class Sdl3 implements AutoCloseable {
    static final int SDL_INIT_VIDEO = 0x00000020;
    static final int SDL_PIXELFORMAT_NV12 = 0x3231564e;
    static final int SDL_TEXTUREACCESS_STATIC = 0;
    // SDL3 renderer native-device properties.
    static final String D3D11_DEVICE_PROPERTY = "SDL.renderer.d3d11.device";
    static final String D3D11_SWAPCHAIN_PROPERTY = "SDL.renderer.d3d11.swapchain";
    static final String METAL_DEVICE_PROPERTY = "SDL.renderer.metal.device";
    static final String METAL_COMMAND_QUEUE_PROPERTY = "SDL.renderer.metal.command_queue";
    static final String VULKAN_DEVICE_PROPERTY = "SDL.renderer.vulkan.device";
    static final String VULKAN_PHYSICAL_DEVICE_PROPERTY = "SDL.renderer.vulkan.physical_device";
    static final String D3D11_TEXTURE_PROPERTY = "SDL.texture.d3d11.texture";

    private final Arena arena = Arena.ofShared();
    private final SymbolLookup symbols;
    private final Linker linker = Linker.nativeLinker();
    private final MethodHandle init, quit, createWindow, destroyWindow, createRenderer,
            destroyRenderer, getRendererName, getRendererProperties, getTextureProperties,
            getPointerProperty, createTexture, destroyTexture, flushRenderer,
            renderTexture, renderPresent, getError;

    Sdl3() {
        String explicit = System.getProperty("scrcpy.sdl3");
        symbols = explicit == null || explicit.isBlank()
                ? SymbolLookup.loaderLookup()
                : SymbolLookup.libraryLookup(Path.of(explicit), arena);
        init = fn("SDL_Init", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT));
        quit = fn("SDL_Quit", FunctionDescriptor.ofVoid());
        createWindow = fn("SDL_CreateWindow",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
        destroyWindow = fn("SDL_DestroyWindow", FunctionDescriptor.ofVoid(ADDRESS));
        createRenderer = fn("SDL_CreateRenderer", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        destroyRenderer = fn("SDL_DestroyRenderer", FunctionDescriptor.ofVoid(ADDRESS));
        getRendererName = fn("SDL_GetRendererName", FunctionDescriptor.of(ADDRESS, ADDRESS));
        getRendererProperties = fn("SDL_GetRendererProperties",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getTextureProperties = fn("SDL_GetTextureProperties",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getPointerProperty = fn("SDL_GetPointerProperty",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        createTexture = fn("SDL_CreateTexture",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        destroyTexture = fn("SDL_DestroyTexture", FunctionDescriptor.ofVoid(ADDRESS));
        flushRenderer = fn("SDL_FlushRenderer", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        renderTexture = fn("SDL_RenderTexture",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        renderPresent = fn("SDL_RenderPresent", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        getError = fn("SDL_GetError", FunctionDescriptor.of(ADDRESS));
    }

    private MethodHandle fn(String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(symbols.find(name)
                .orElseThrow(() -> new IllegalStateException("SDL3 symbol not found: " + name)), descriptor);
    }

    boolean initVideo() { return (boolean) invoke(init, SDL_INIT_VIDEO); }

    MemorySegment createWindow(String title, int width, int height) {
        return (MemorySegment) invoke(createWindow, arena.allocateFrom(title), width, height, 0L);
    }

    MemorySegment createRenderer(MemorySegment window, String name) {
        return (MemorySegment) invoke(createRenderer, window,
                name == null ? MemorySegment.NULL : arena.allocateFrom(name));
    }

    String rendererName(MemorySegment renderer) {
        return cString((MemorySegment) invoke(getRendererName, renderer));
    }

    MemorySegment rendererPointerProperty(MemorySegment renderer, String property) {
        int props = (int) invoke(getRendererProperties, renderer);
        return props == 0 ? MemorySegment.NULL :
                (MemorySegment) invoke(getPointerProperty, props, arena.allocateFrom(property), MemorySegment.NULL);
    }

    MemorySegment rendererD3d11Device(MemorySegment renderer) {
        return rendererPointerProperty(renderer, D3D11_DEVICE_PROPERTY);
    }

    MemorySegment createNv12Texture(MemorySegment renderer, int width, int height) {
        return (MemorySegment) invoke(createTexture, renderer, SDL_PIXELFORMAT_NV12,
                SDL_TEXTUREACCESS_STATIC, width, height);
    }

    MemorySegment textureD3d11Texture(MemorySegment texture) {
        int props = (int) invoke(getTextureProperties, texture);
        return props == 0 ? MemorySegment.NULL :
                (MemorySegment) invoke(getPointerProperty, props, arena.allocateFrom(D3D11_TEXTURE_PROPERTY),
                        MemorySegment.NULL);
    }

    boolean flushRenderer(MemorySegment renderer) {
        return (boolean) invoke(flushRenderer, renderer);
    }

    boolean renderTexture(MemorySegment renderer, MemorySegment texture) {
        return (boolean) invoke(renderTexture, renderer, texture, MemorySegment.NULL, MemorySegment.NULL);
    }

    boolean renderPresent(MemorySegment renderer) {
        return (boolean) invoke(renderPresent, renderer);
    }

    void destroyTexture(MemorySegment texture) {
        if (texture != null && texture.address() != 0) invoke(destroyTexture, texture);
    }

    String error() { return cString((MemorySegment) invoke(getError)); }

    void destroyRenderer(MemorySegment renderer) {
        if (renderer != null && renderer.address() != 0) invoke(destroyRenderer, renderer);
    }

    void destroyWindow(MemorySegment window) {
        if (window != null && window.address() != 0) invoke(destroyWindow, window);
    }

    private static String cString(MemorySegment pointer) {
        return pointer == null || pointer.address() == 0 ? null :
                pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try { return handle.invokeWithArguments(args); }
        catch (Throwable t) { throw new IllegalStateException("SDL3 call failed", t); }
    }

    @Override public void close() {
        invoke(quit);
        arena.close();
    }
}