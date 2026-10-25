package com.jian.scrcpy;

import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.server.ScrcpyOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScrcpySessionTest {

    @Test
    void startsInNewStateAndCloseIsTerminal() {
        ScrcpySession session = new ScrcpySession(new Adb("adb", "test"), ScrcpyOptions.defaults());

        assertEquals(ScrcpySession.State.NEW, session.state());
        assertFalse(session.sendControl(new ControlMessages.BackOrScreenOn((byte) 0)));

        session.close();

        assertEquals(ScrcpySession.State.STOPPED, session.state());
        assertFalse(session.sendControl(new ControlMessages.BackOrScreenOn((byte) 0)));
        assertDoesNotThrow(session::close);
    }

    @Test
    void stoppedSessionCannotBeStartedAgain() {
        ScrcpySession session = new ScrcpySession(new Adb("adb", "test"), ScrcpyOptions.defaults());
        session.close();

        assertThrows(IllegalStateException.class,
                () -> session.start(Path.of("missing-server.jar"), frame -> {}));
    }
}
