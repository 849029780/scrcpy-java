package com.jian.scrcpy.app;

import java.util.Map;

/**
 * Runtime diagnostics for the JavaFX Prism renderer.
 *
 * <p>The FFmpeg hardware decoder currently exposes D3D11 textures. Before
 * attempting native texture sharing, identify the actual Prism backend:
 * stock JavaFX Windows D3D is not the same API/device as FFmpeg D3D11VA.
 * This probe deliberately uses reflection so the application does not acquire
 * a compile-time dependency on unsupported com.sun.prism APIs.</p>
 */
final class PrismPipelineDiagnostics {
    private PrismPipelineDiagnostics() {
    }

    static void log() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }

        try {
            Class<?> graphicsPipeline = Class.forName("com.sun.prism.GraphicsPipeline");
            Object pipeline = graphicsPipeline.getMethod("getPipeline").invoke(null);
            if (pipeline == null) {
                System.out.println("[scrcpy-java] JavaFX Prism pipeline is not initialized yet");
                return;
            }

            String pipelineName = pipeline.getClass().getName();
            System.out.println("[scrcpy-java] JavaFX Prism pipeline: " + pipelineName);

            try {
                Object details = graphicsPipeline.getMethod("getDeviceDetails").invoke(pipeline);
                if (details instanceof Map<?, ?> map && !map.isEmpty()) {
                    System.out.println("[scrcpy-java] JavaFX Prism device details: " + map);
                }
            } catch (ReflectiveOperationException ignored) {
                // Device details are diagnostic only and differ between Prism backends.
            }

            if (pipelineName.contains(".prism.d3d.D3DPipeline")) {
                System.out.println("[scrcpy-java] GPU interop assessment: stock Prism D3D backend "
                        + "uses the Direct3D 9 resource model; FFmpeg D3D11VA textures cannot "
                        + "be attached directly. A native Prism resource-import extension or "
                        + "a D3D11-to-D3D9 shared-resource bridge is required.");
            } else if (pipelineName.contains(".prism.d3d12.")) {
                System.out.println("[scrcpy-java] GPU interop assessment: Prism D3D12 is active; "
                        + "FFmpeg D3D11VA resources still require an explicit D3D11/D3D12 "
                        + "interop or copy bridge.");
            } else {
                System.out.println("[scrcpy-java] GPU interop assessment: Prism backend is not "
                        + "the stock D3D backend; a backend-specific texture-sharing bridge "
                        + "must be selected.");
            }
        } catch (Throwable error) {
            System.err.println("[scrcpy-java] unable to inspect JavaFX Prism pipeline: " + error);
            System.err.println("[scrcpy-java] add --add-exports=javafx.graphics/com.sun.prism=ALL-UNNAMED "
                    + "to the JavaFX VM options to enable this diagnostic.");
        }
    }
}
