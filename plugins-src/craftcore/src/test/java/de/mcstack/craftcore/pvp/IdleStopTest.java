package de.mcstack.craftcore.pvp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleStopTest {
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
