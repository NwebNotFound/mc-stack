package de.mcstack.craftcore.pvp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleStopTest {
    @Test void onlyFreshSuccessfulPendingChecksKeepServerBusy() {
        assertTrue(IdleStop.busy(true, null, Long.MAX_VALUE, false));
        assertFalse(IdleStop.busy(false, null, 0, false));
        assertFalse(IdleStop.busy(false, false, 0, false));
        assertTrue(IdleStop.busy(false, true, 29_999, false));
        assertFalse(IdleStop.busy(false, true, 30_000, false));
        assertFalse(IdleStop.busy(false, true, -1, false));
    }
    @Test void buildOccupantsKeepServerBusy() {
        assertTrue(IdleStop.busy(false, null, 0, true));
        assertFalse(IdleStop.busy(false, null, 0, false));
    }
    @Test void pendingAssignmentHoldsTheIdleTimer() {
        IdleStop timer = new IdleStop(IdleStop.DEFAULT_GRACE_MS);
        assertFalse(timer.shouldStop(0, false, true, 0, false));
        assertFalse(timer.shouldStop(180_000, false, true, 1_000, false));
        assertFalse(timer.shouldStop(180_001, false, false, 0, false));
        assertTrue(timer.shouldStop(300_001, false, false, 0, false));
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
