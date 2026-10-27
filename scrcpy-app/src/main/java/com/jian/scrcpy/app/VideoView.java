package com.jian.scrcpy.app;

import com.jian.scrcpy.media.VideoFrame;
import javafx.application.Platform;
import javafx.scene.image.*;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class VideoView extends ImageView {
    private final AtomicReference<VideoFrame> latest = new AtomicReference<>();
    private boolean scheduled;
    private volatile int frameWidth;
    private volatile int frameHeight;

    // One PixelBuffer/WritableImage pair is created for each of the decoder's
    // pooled direct buffers. WritableImage uses PixelBuffer storage directly,
    // avoiding PixelWriter's full-frame copy into a separate JavaFX image.
    private final Map<ByteBuffer, PixelSurface> surfaces = new IdentityHashMap<>();
    private VideoFrame displayed;
    private int imageWidth;
    private int imageHeight;

    private record PixelSurface(PixelBuffer<ByteBuffer> pixels, WritableImage image) {}

    public VideoView() {
        setPreserveRatio(true);
        setSmooth(true);
    }

    public void accept(VideoFrame f) {
        frameWidth = f.width();
        frameHeight = f.height();
        VideoFrame replaced = latest.getAndSet(f);
        if (replaced != null) {
            replaced.close();
        }
        scheduleRender();
    }

    private void scheduleRender() {
        synchronized (this) {
            if (scheduled) return;
            scheduled = true;
        }
        Platform.runLater(this::renderLatest);
    }

    private void renderLatest() {
        try {
            VideoFrame v = latest.getAndSet(null);
            if (v != null) {
                if (imageWidth != v.width() || imageHeight != v.height()) {
                    surfaces.clear();
                    imageWidth = v.width();
                    imageHeight = v.height();
                }

                PixelSurface surface = surfaces.computeIfAbsent(v.bgra(), buffer -> {
                    PixelBuffer<ByteBuffer> pixels = new PixelBuffer<>(
                            v.width(),
                            v.height(),
                            buffer,
                            PixelFormat.getByteBgraPreInstance());
                    return new PixelSurface(pixels, new WritableImage(pixels));
                });

                // updateBuffer must run on the FX thread (we are in runLater).
                // FFmpeg has already written the complete frame, so null marks
                // the whole image dirty without another Java-side pixel copy.
                surface.pixels().updateBuffer(ignored -> null);
                setImage(surface.image());

                // Keep the currently displayed buffer owned until a newer image
                // has been installed. This prevents the decoder from overwriting
                // the buffer while JavaFX/Prism may still consume it.
                VideoFrame previous = displayed;
                displayed = v;
                if (previous != null) {
                    previous.close();
                }
            }
        } finally {
            boolean reschedule;
            synchronized (this) {
                scheduled = false;
                reschedule = latest.get() != null;
                if (reschedule) scheduled = true;
            }
            if (reschedule) {
                Platform.runLater(this::renderLatest);
            }
        }
    }

    public int frameWidth() { return frameWidth; }
    public int frameHeight() { return frameHeight; }
}
