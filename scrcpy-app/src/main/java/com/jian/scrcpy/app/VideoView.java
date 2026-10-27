package com.jian.scrcpy.app;

import com.jian.scrcpy.media.VideoFrame;
import javafx.application.Platform;
import javafx.scene.image.*;

import java.util.concurrent.atomic.AtomicReference;

public final class VideoView extends ImageView {
    private final AtomicReference<VideoFrame> latest = new AtomicReference<>();
    private boolean scheduled;
    private volatile int frameWidth;
    private volatile int frameHeight;

    // Reuse the JavaFX image instead of allocating a new WritableImage for
    // every decoded frame. Multiple 720p devices otherwise create a large
    // amount of JavaFX/Prism native graphics allocation churn.
    private WritableImage image;
    private PixelWriter pixelWriter;
    private int imageWidth;
    private int imageHeight;

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
                try {
                    if (image == null || imageWidth != v.width() || imageHeight != v.height()) {
                        image = new WritableImage(v.width(), v.height());
                        pixelWriter = image.getPixelWriter();
                        imageWidth = v.width();
                        imageHeight = v.height();
                        setImage(image);
                    }
                    pixelWriter.setPixels(
                            0, 0,
                            v.width(), v.height(),
                            PixelFormat.getByteBgraInstance(),
                            v.bgra(), 0, v.width() * 4);
                } finally {
                    // PixelWriter has copied the source bytes before returning,
                    // so the decoder can safely reuse this large BGRA buffer.
                    v.close();
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

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }
}