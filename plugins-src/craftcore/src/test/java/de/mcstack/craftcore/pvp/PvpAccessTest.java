package de.mcstack.craftcore.pvp;

import java.util.Set;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvpAccessTest {
    @Test void joinModeKeepsAssignedPlayersInAdventure() {
        assertEquals(GameMode.CREATIVE, PvpAccess.joinMode(true, false));
        assertEquals(GameMode.ADVENTURE, PvpAccess.joinMode(true, true));
        assertEquals(GameMode.ADVENTURE, PvpAccess.joinMode(false, false));
    }
    @Test void assignmentTakesPriorityAndBypassOnlyAppliesWithoutOne() {
        assertEquals(PvpAccess.JoinDecision.MATCH, PvpAccess.decide(true, false));
        assertEquals(PvpAccess.JoinDecision.MATCH, PvpAccess.decide(true, true));
        assertEquals(PvpAccess.JoinDecision.BUILDER, PvpAccess.decide(false, true));
        assertEquals(PvpAccess.JoinDecision.KICK, PvpAccess.decide(false, false));
    }
    @Test void onlyBuildersWithoutAssignmentAreSentToBuildWorld() {
        assertTrue(PvpAccess.sendToBuildWorld(PvpAccess.decide(false, true)));
        assertFalse(PvpAccess.sendToBuildWorld(PvpAccess.decide(true, true)));
        assertFalse(PvpAccess.sendToBuildWorld(PvpAccess.decide(false, false)));
    }
    @Test void pendingBuildRequestLandsInBuildWorldEvenWithAnAssignment() {
        assertEquals(PvpAccess.JoinDecision.BUILDER, PvpAccess.destination(true, true, false));
        assertEquals(PvpAccess.JoinDecision.BUILDER, PvpAccess.destination(true, false, false));
        assertTrue(PvpAccess.sendToBuildWorld(PvpAccess.destination(true, true, true)));
        assertEquals(PvpAccess.JoinDecision.MATCH, PvpAccess.destination(false, true, true));
        assertEquals(PvpAccess.JoinDecision.KICK, PvpAccess.destination(false, false, false));
    }
    @Test void eachPermissionAllowsBypass() {
        for (String permission : Set.of("craftcore.build.pvp", "group.builder", "group.admin")) {
            assertTrue(PvpAccess.mayBypass(permission::equals));
        }
    }

    @Test void otherPermissionsDoNotAllowBypass() {
        assertFalse(PvpAccess.mayBypass(Set.of("group.member")::contains));
    }
    @Test void leaveAndTeleportsOutOfTheMatchAreRefusedUntilItFinishes() {
        // Issue #155: a losing player escaped with /pvp leave and forced a draw.
        assertFalse(PvpAccess.mayLeave(true));
        assertTrue(PvpAccess.mayLeave(false));
        assertTrue(PvpAccess.blocksTeleport("pvp_m_12345678", false, "pvp_m_12345678", "world"));
        assertFalse(PvpAccess.blocksTeleport("pvp_m_12345678", true, "pvp_m_12345678", "world"), "cleanup teleports after finishing");
        assertFalse(PvpAccess.blocksTeleport("pvp_m_12345678", false, "pvp_m_12345678", "pvp_m_12345678"), "pearls inside the arena");
        assertFalse(PvpAccess.blocksTeleport("pvp_m_12345678", false, "world", "pvp_m_12345678"), "the round start teleports in");
        assertFalse(PvpAccess.blocksTeleport(null, false, "world", "pvp_build"));
    }
}
