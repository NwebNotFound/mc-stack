package de.mcstack.craftcore.pvp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleStopTest {
    @Test void onlyFreshSuccessfulPendingChecksKeepServerBusy() {
        assertTrue(IdleStop.busy(true, null, Long.MAX_VALUE, false, false));
        assertFalse(IdleStop.busy(false, null, 0, false, false));
        assertFalse(IdleStop.busy(false, false, 0, false, false));
        assertTrue(IdleStop.busy(false, true, 29_999, false, false));
        assertFalse(IdleStop.busy(false, true, 30_000, false, false));
        assertFalse(IdleStop.busy(false, true, -1, false, false));
    }
    @Test void draftsAndBuildOccupantsKeepServerBusy() {
        assertTrue(IdleStop.busy(false, null, 0, true, false));
        assertTrue(IdleStop.busy(false, null, 0, false, true));
        assertFalse(IdleStop.busy(false, null, 0, false, false));
    }
    @Test void graceAndBusyReset() {
        IdleStop timer = new IdleStop(IdleStop.DEFAULT_GRACE_MS);
        assertFalse(timer.tick(1_000, false));
        assertFalse(timer.tick(120_999, false));
        assertTrue(timer.tick(121_000, false));
        assertFalse(timer.tick(121_001, true));
        assertFalse(timer.tick(200_000, false));
        assertFalse(timer.tick(319_999, false));
        assertTrue(timer.tick(320_000, false));
    }
}
