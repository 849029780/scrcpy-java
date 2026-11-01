package com.jian.scrcpy.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Minimal integration test: Swing JFrame -> heavyweight Canvas -> JAWT HWND
 * -> D3D11 swap chain. The native renderer clears the Canvas to a test color.
 */
public final class SwingD3D11PoC {
    private SwingD3D11PoC() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SwingD3D11PoC::showWindow);
    }

    private static void showWindow() {
        JFrame frame = new JFrame("scrcpy-java - Swing / JAWT / D3D11 PoC");
        NativeD3D11Canvas videoSurface = new NativeD3D11Canvas();
        JLabel status = new JLabel("Initializing native D3D11 renderer...");
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        frame.setLayout(new BorderLayout());
        frame.add(status, BorderLayout.NORTH);
        frame.add(videoSurface, BorderLayout.CENTER);
        frame.setSize(1100, 760);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                try {
                    videoSurface.attachNativeRenderer();
                    status.setText("D3D11 swap chain attached to AWT Canvas HWND");
                } catch (Throwable error) {
                    status.setText("D3D11 initialization failed: " + error.getMessage());
                    error.printStackTrace(System.err);
                }
            }

            @Override
            public void windowClosed(WindowEvent event) {
                videoSurface.close();
            }
        });

        Timer renderTimer = new Timer(16, event -> videoSurface.renderFrame());
        renderTimer.start();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                renderTimer.stop();
            }
        });
        frame.setVisible(true);
    }
}
