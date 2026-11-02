package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Retry of the proxy {@code Connect}: three attempts, two seconds apart, only while still here. */
class VipConnectRetryTest {
    @Test void threeAttemptsTwoSecondsApart() {
        assertEquals(3, VipConnectRetry.ATTEMPTS);
        assertEquals(2_000L, VipConnectRetry.DELAY_MILLIS);
        assertEquals(0L, VipConnectRetry.delayTicks(0), "der erste Versuch geht sofort raus");
        assertEquals(40L, VipConnectRetry.delayTicks(1));
        assertEquals(80L, VipConnectRetry.delayTicks(2));
    }

    @Test void sendsOnlyWhileThePlayerIsStillOnThisServer() {
        assertTrue(VipConnectRetry.maySend(0, true, true));
        assertTrue(VipConnectRetry.maySend(2, true, true));
        assertFalse(VipConnectRetry.maySend(3, true, true), "kein vierter Versuch");
        assertFalse(VipConnectRetry.maySend(0, false, true), "Spieler offline");
        assertFalse(VipConnectRetry.maySend(0, true, false), "Spieler hat den Server schon gewechselt");
        assertFalse(VipConnectRetry.maySend(-1, true, true));
    }
}
