package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class VipLimitsTest {
    @Test void vipAndMvpPlayerLimits() {
        assertEquals(5, VipLimits.playerLimit("vip", 5, 10, 10));
        assertEquals(10, VipLimits.playerLimit("mvp", 5, 10, 10));
        assertEquals(10, VipLimits.playerLimit("admin", 5, 10, 10));
        assertEquals(0, VipLimits.playerLimit("default", 5, 10, 10));
        assertEquals(0, VipLimits.playerLimit(null, 5, 10, 10));
    }

    @Test void configuredLimitNeverExceedsFifteen() {
        assertEquals(15, VipLimits.clampPlayers(99));
        assertEquals(15, VipLimits.playerLimit("mvp", 5, 99, 99));
        assertEquals(1, VipLimits.clampPlayers(0));
        assertEquals(1, VipLimits.clampPlayers(-7));
    }

    @Test void highestRankWins() {
        assertEquals("admin", VipLimits.rank(List.of("default", "vip", "admin")));
        assertEquals("mvp", VipLimits.rank(List.of("vip", "mvp")));
        assertEquals("vip", VipLimits.rank(List.of("default", "vip")));
        assertEquals("", VipLimits.rank(List.of("default")));
        assertEquals("", VipLimits.rank(null));
    }

    @Test void adminSlotsAreAFloorAndCapAtThree() {
        assertEquals(1, VipLimits.slots("1", null, false, 3));
        assertEquals(3, VipLimits.slots(null, null, true, 3));
        assertEquals(3, VipLimits.slots("2", null, true, 3));
        assertEquals(3, VipLimits.slots("9", null, false, 3));
        assertEquals(0, VipLimits.slots(null, null, false, 3));
        assertEquals(2, VipLimits.slots(null, "2", false, 3));
        assertEquals(0, VipLimits.parse("abc"));
    }

    @Test void fullWorldRules() {
        assertTrue(VipLimits.atCap(5, 5));
        assertFalse(VipLimits.atCap(4, 5));
        assertFalse(VipLimits.atCap(5, 0));
        assertTrue(VipLimits.mayJoinFull(true, false));
        assertTrue(VipLimits.mayJoinFull(false, true));
        assertFalse(VipLimits.mayJoinFull(false, false));
    }
}
