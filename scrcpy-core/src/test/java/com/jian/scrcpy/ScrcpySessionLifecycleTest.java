package com.jian.scrcpy;

import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.server.ScrcpyOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScrcpySessionLifecycleTest {
    @Test
    void closeIsTerminalAndControlIsRejected() {
        ScrcpySession s = new ScrcpySession(new Adb("adb", "test"), ScrcpyOptions.defaults());
        assertEquals(ScrcpySession.State.NEW, s.state());
        assertFalse(s.sendControl(new ControlMessages.BackOrScreenOn((byte) 0)));
        s.close();
        assertEquals(ScrcpySession.State.STOPPED, s.state());
        assertFalse(s.sendControl(new ControlMessages.BackOrScreenOn((byte) 0)));
        assertDoesNotThrow(s::close);
    }

    @Test
    void stoppedSessionCannotRestart() {
        ScrcpySession s = new ScrcpySession(new Adb("adb", "test"), ScrcpyOptions.defaults());
        s.close();
        assertThrows(IllegalStateException.class,
                () -> s.start(java.nio.file.Path.of("missing-server.jar"), (io.netty.channel.ChannelInboundHandler) null, f -> {}));
    }
}
