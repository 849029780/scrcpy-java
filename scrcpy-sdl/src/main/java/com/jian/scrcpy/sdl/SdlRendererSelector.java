package com.jian.scrcpy.sdl;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Locale;

/**
 * Selects an SDL renderer by graphics API capability, never by GPU marketing
 * name. This keeps Intel/AMD/NVIDIA and integrated/discrete GPUs on the same
 * code path and gives each operating system an ordered fallback chain.
 */
final class SdlRendererSelector {
    enum Interop {
        D3D11,
        METAL,
        VULKAN,
        SOFTWARE
    }

    record Selection(
            MemorySegment renderer,
            String rendererName,
            Interop interop,
            MemorySegment device,
            MemorySegment auxiliaryDevice) {}

    private SdlRendererSelector() {}

    static Selection create(Sdl3 sdl, MemorySegment window) {
        for (String candidate : candidates()) {
            MemorySegment renderer = sdl.createRenderer(window, candidate);
            if (renderer.address() == 0) {
                continue;
            }

            String name = sdl.rendererName(renderer);
            Interop interop = classify(name);
            MemorySegment device = primaryDevice(sdl, renderer, interop);
            MemorySegment auxiliary = auxiliaryDevice(sdl, renderer, interop);

            // A hardware interop backend is only selected when SDL actually
            // exposes the native device required to share it with FFmpeg.
            if (interop == Interop.SOFTWARE || device.address() != 0) {
                return new Selection(renderer, name, interop, device, auxiliary);
            }

            sdl.destroyRenderer(renderer);
        }

        throw new IllegalStateException("No usable SDL renderer: " + sdl.error());
    }

    private static List<String> candidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // D3D11 matches FFmpeg D3D11VA and exposes ID3D11Device*.
            // Other accelerated APIs are deliberately not selected yet because
            // they cannot share the existing D3D11VA frame without another
            // cross-API interop layer.
            return List.of("direct3d11", "software");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("metal", "software");
        }
        if (os.contains("linux")) {
            // Vulkan is the long-term common renderer. VAAPI/DRM PRIME import
            // will be implemented behind the same Interop abstraction.
            return List.of("vulkan", "opengl", "software");
        }
        return List.of("software");
    }

    private static Interop classify(String name) {
        if (name == null) return Interop.SOFTWARE;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "direct3d11" -> Interop.D3D11;
            case "metal" -> Interop.METAL;
            case "vulkan" -> Interop.VULKAN;
            default -> Interop.SOFTWARE;
        };
    }

    private static MemorySegment primaryDevice(Sdl3 sdl, MemorySegment renderer, Interop interop) {
        return switch (interop) {
            case D3D11 -> sdl.rendererPointerProperty(renderer, Sdl3.D3D11_DEVICE_PROPERTY);
            case METAL -> sdl.rendererPointerProperty(renderer, Sdl3.METAL_DEVICE_PROPERTY);
            case VULKAN -> sdl.rendererPointerProperty(renderer, Sdl3.VULKAN_DEVICE_PROPERTY);
            case SOFTWARE -> MemorySegment.NULL;
        };
    }

    private static MemorySegment auxiliaryDevice(Sdl3 sdl, MemorySegment renderer, Interop interop) {
        return switch (interop) {
            case D3D11 -> sdl.rendererPointerProperty(renderer, Sdl3.D3D11_SWAPCHAIN_PROPERTY);
            case METAL -> sdl.rendererPointerProperty(renderer, Sdl3.METAL_COMMAND_QUEUE_PROPERTY);
            case VULKAN -> sdl.rendererPointerProperty(renderer, Sdl3.VULKAN_PHYSICAL_DEVICE_PROPERTY);
            case SOFTWARE -> MemorySegment.NULL;
        };
    }
}
