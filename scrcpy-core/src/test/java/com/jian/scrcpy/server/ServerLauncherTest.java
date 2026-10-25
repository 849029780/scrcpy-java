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
                "h264", "opus",
                (long) Integer.MAX_VALUE + 1,
                0, 0, null,
                false, false, false, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> ServerLauncher.buildServerArgs(options, "00000001"));
    }
}
