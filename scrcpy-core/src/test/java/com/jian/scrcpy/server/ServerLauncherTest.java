package com.jian.scrcpy.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerLauncherTest {

    @Test
    void forwardsConnectionSettingsToServer() {
        ScrcpyOptions options = new ScrcpyOptions(
                "serial",
                123,
                27183,
                true,
                true,
                true,
                "h265",
                null,
                null,
                "aac",
                8_000_000,
                192_000,
                1920,
                "60",
                true,
                true,
                true,
                "1080:1920:0:0",
                "com.example.app");

        List<String> args = ServerLauncher.buildServerArgs(options, "0000007b");

        assertTrue(args.contains("video_codec=h265"));
        assertTrue(args.contains("audio_codec=aac"));
        assertTrue(args.contains("video_bit_rate=8000000"));
        assertTrue(args.contains("audio_bit_rate=192000"));
        assertTrue(args.contains("max_size=1920"));
        assertTrue(args.contains("max_fps=60"));
        assertTrue(args.contains("crop=1080:1920:0:0"));
        assertTrue(args.contains("show_touches=true"));
        assertTrue(args.contains("stay_awake=true"));

        // turn_screen_off is a desktop-client control message, not a server option.
        assertFalse(args.stream().anyMatch(a -> a.startsWith("turn_screen_off=")));
        // start_app is also sent through the control socket after it is connected.
        assertFalse(args.stream().anyMatch(a -> a.startsWith("start_app=")));
    }

    @Test
    void omitsDefaultOrUnlimitedServerOptions() {
        ScrcpyOptions options = ScrcpyOptions.defaults();

        List<String> args = ServerLauncher.buildServerArgs(options, "00000001");

        assertFalse(args.stream().anyMatch(a -> a.startsWith("video_codec=")));
        assertFalse(args.stream().anyMatch(a -> a.startsWith("audio_codec=")));
        assertFalse(args.stream().anyMatch(a -> a.startsWith("video_bit_rate=")));
        assertFalse(args.stream().anyMatch(a -> a.startsWith("audio_bit_rate=")));
        assertFalse(args.stream().anyMatch(a -> a.startsWith("max_size=")));
        assertFalse(args.stream().anyMatch(a -> a.startsWith("max_fps=")));
        assertFalse(args.stream().anyMatch(a -> a.startsWith("crop=")));
        assertFalse(args.contains("show_touches=true"));
        assertFalse(args.contains("stay_awake=true"));
    }

    @Test
    void rejectsServerBitRateOutsideAndroidIntRange() {
        ScrcpyOptions options = new ScrcpyOptions(
                null, 1, 27183,
                true, true, true,
                "h264", null, null, "auto", "opus",
                (long) Integer.MAX_VALUE + 1,
                0, 0, null,
                false, false, false, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> ServerLauncher.buildServerArgs(options, "00000001"));
    }
    @Test
    void parsesVideoEncoderList() {
        String output = """
                [server] INFO: List of video encoders:
                    --video-codec=h264 --video-encoder='OMX.qcom.video.encoder.avc' (hw) [vendor]
                    --video-codec=h264 --video-encoder='c2.android.avc.encoder' (sw)
                    --video-codec=h265 --video-encoder='c2.android.hevc.encoder' (sw)
                    --video-codec=av1 --video-encoder='c2.android.av1.encoder' (sw)
                """;

        List<ServerLauncher.VideoEncoder> encoders = ServerLauncher.parseVideoEncoders(output);

        assertEquals(4, encoders.size());
        assertEquals("h264", encoders.get(0).codec());
        assertEquals("OMX.qcom.video.encoder.avc", encoders.get(0).name());
        assertTrue(encoders.get(0).hardware());
        assertFalse(encoders.get(1).hardware());
        assertEquals("h265", encoders.get(2).codec());
        assertEquals("c2.android.av1.encoder", encoders.get(3).name());
    }


}
