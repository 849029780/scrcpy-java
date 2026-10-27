package com.jian.scrcpy.sdl;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

final class Sdl3 implements AutoCloseable {
    static final int SDL_INIT_VIDEO = 0x00000020;
    static final String D3D11_DEVICE_PROPERTY = "SDL.renderer.d3d11.device";

    private final Arena arena = Arena.ofShared();
    private final SymbolLookup symbols;
    private final Linker linker = Linker.nativeLinker();

    private final MethodHandle init;
    private final MethodHandle quit;
    private final MethodHandle createWindow;
    private final MethodHandle destroyWindow;
    private final MethodHandle createRenderer;
    private final MethodHandle destroyRenderer;
    private final MethodHandle getRendererName;
    private final MethodHandle getRendererProperties;
    private final MethodHandle getPointerProperty;
    private final MethodHandle getError;

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
        createRenderer = fn("SDL_CreateRenderer",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        destroyRenderer = fn("SDL_DestroyRenderer", FunctionDescriptor.ofVoid(ADDRESS));
        getRendererName = fn("SDL_GetRendererName", FunctionDescriptor.of(ADDRESS, ADDRESS));
        getRendererProperties = fn("SDL_GetRendererProperties",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getPointerProperty = fn("SDL_GetPointerProperty",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        getError = fn("SDL_GetError", FunctionDescriptor.of(ADDRESS));
    }

    private MethodHandle fn(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = symbols.find(name)
                .orElseThrow(() -> new IllegalStateException("SDL3 symbol not found: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    boolean initVideo() { return (boolean) invoke(init, SDL_INIT_VIDEO); }

    MemorySegment createWindow(String title, int width, int height) {
        return (MemorySegment) invoke(createWindow, utf8(title), width, height, 0L);
    }

    MemorySegment createRenderer(MemorySegment window, String name) {
        return (MemorySegment) invoke(createRenderer, window,
                name == null ? MemorySegment.NULL : utf8(name));
    }

    String rendererName(MemorySegment renderer) {
        return cString((MemorySegment) invoke(getRendererName, renderer));
    }

    MemorySegment rendererD3d11Device(MemorySegment renderer) {
        int props = (int) invoke(getRendererProperties, renderer);
        return (MemorySegment) invoke(getPointerProperty, props,
                utf8(D3D11_DEVICE_PROPERTY), MemorySegment.NULL);
    }

    String error() { return cString((MemorySegment) invoke(getError)); }

    void destroyRenderer(MemorySegment renderer) {
        if (renderer != null && renderer.address() != 0) invoke(destroyRenderer, renderer);
    }

    void destroyWindow(MemorySegment window) {
        if (window != null && window.address() != 0) invoke(destroyWindow, window);
    }

    private MemorySegment utf8(String value) { return arena.allocateFrom(value); }

    private static String cString(MemorySegment pointer) {
        return pointer == null || pointer.address() == 0
                ? null : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new IllegalStateException("SDL3 call failed", t);
        }
    }

    @Override
    public void close() {
        invoke(quit);
        arena.close();
    }
}
