package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VipConfigTest {
    private static VipConfig.Source source(Map<String, Integer> ints, Map<String, Boolean> bools) {
        return new VipConfig.Source() {
            public int getInt(String path, int fallback) { return ints.getOrDefault(path, fallback); }
            public boolean getBoolean(String path, boolean fallback) { return bools.getOrDefault(path, fallback); }
        };
    }

    @Test void defaultsAreVipFiveMvpTenAndMaxFifteen() {
        VipConfig.Values values = VipConfig.read(source(Map.of(), Map.of()));
        assertEquals(5, values.players("vip"));
        assertEquals(10, values.players("mvp"));
        assertEquals(10, values.players("admin"));
        assertEquals(15, values.maxPlayers());
        assertEquals(3, values.adminSlots());
        assertEquals(3, values.addonMax());
        assertEquals(180, values.startTimeoutSeconds());
        assertTrue(values.trustedMayStart());
    }

    @Test void configuredValuesAreClampedToFifteen() {
        VipConfig.Values values = VipConfig.read(source(Map.of(
            "players.vip", 5, "players.mvp", 40, "players.admin", 99, "players.max", 15), Map.of()));
        assertEquals(5, values.players("vip"));
        assertEquals(15, values.players("mvp"));
        assertEquals(15, values.players("admin"));
    }

    @Test void aLowerMaxCapsEveryRank() {
        VipConfig.Values values = VipConfig.read(source(Map.of(
            "players.vip", 5, "players.mvp", 10, "players.admin", 10, "players.max", 8), Map.of()));
        assertEquals(5, values.players("vip"));
        assertEquals(8, values.players("mvp"));
        assertEquals(8, values.players("admin"));
    }

    @Test void addonAndSlotLimitsAreBounded() {
        VipConfig.Values values = VipConfig.read(source(Map.of(
            "admin-slots", 9, "addons.max", 9, "start-timeout-seconds", 5,
            "start-poll-seconds", 0, "start-max-errors", 0), Map.of("trusted-may-start", false)));
        assertEquals(VipLimits.MAX_SLOTS, values.adminSlots());
        assertEquals(VipAddons.MAX, values.addonMax());
        assertEquals(30, values.startTimeoutSeconds());
        assertEquals(1, values.pollSeconds());
        assertEquals(1, values.maxErrors());
        assertFalse(values.trustedMayStart());
    }

    @Test void unknownRankHasNoLimit() {
        VipConfig.Values values = VipConfig.read(source(new HashMap<>(), new HashMap<>()));
        assertEquals(0, values.players("default"));
        assertEquals(0, values.players(null));
    }
}
