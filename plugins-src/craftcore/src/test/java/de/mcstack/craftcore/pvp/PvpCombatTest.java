package de.mcstack.craftcore.pvp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvpCombatTest {
    @Test void snowballEggAndFishingHitsKeepKnockbackDuringARound() {
        for (String type : new String[] {"SNOWBALL", "EGG", "FISHING_BOBBER"}) {
            assertTrue(PvpCombat.keepsKnockback(true, type));
            assertFalse(PvpCombat.keepsKnockback(false, type));
        }
        assertFalse(PvpCombat.keepsKnockback(true, "ARROW"));
        assertFalse(PvpCombat.keepsKnockback(false, "ARROW"));
    }
    @Test void snowballPushesThePlayerAwayFromTheThrower() {
        double[] velocity = PvpCombat.snowballKnockback(0, 0, 0, 1, 0, 0, 0, true);
        assertTrue(velocity[0] < 0, "pushed away from the thrower on +X");
        assertEquals(0, velocity[2], 1e-9);
        assertTrue(velocity[1] > 0);
    }
    @Test void fishingRodPullsThePlayerTowardTheFisher() {
        double[] velocity = PvpCombat.reelPull(0, 0, 0, 0, 0, 0, 10, 0, 0);
        assertTrue(velocity[0] > 0, "pulled toward the fisher on +X");
        assertEquals(0, velocity[2], 1e-9);
    }
}
