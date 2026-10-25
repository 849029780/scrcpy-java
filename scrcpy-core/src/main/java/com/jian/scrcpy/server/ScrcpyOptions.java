package com.jian.scrcpy.server;

import java.util.concurrent.ThreadLocalRandom;

public record ScrcpyOptions(
        String serial,
        int scid,
        int localPort,
        boolean video,
        boolean audio,
        boolean control,
        String videoCodec,
        String videoEncoder,
        String videoCodecOptions,
        String audioCodec,
        long videoBitRate,
        long audioBitRate,
        int maxSize,
        String maxFps,
        boolean showTouches,
        boolean stayAwake,
        boolean turnScreenOff,
        String crop,
        String startApp) {

    public static ScrcpyOptions defaults() {
        // scrcpy 5.0.1 parses scid with Integer.parseInt(value, 16),
        // so the generated value must stay in the signed 31-bit range.
        int scid = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        return new ScrcpyOptions(
                null,
                scid,
                0,
                true,
                true,
                true,
                "h264",
                null,
                null,
                "opus",
                0,
                0,
                0,
                null,
                false,
                false,
                false,
                null,
                null);
    }
}
