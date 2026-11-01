package de.mcstack.craftcore.pvp;

import java.util.function.Predicate;
import org.bukkit.GameMode;

public final class PvpAccess {
    private PvpAccess() {}
    public enum JoinDecision { MATCH, BUILDER, KICK }
    public static JoinDecision decide(boolean hasAssignment, boolean bypass) {
        return hasAssignment ? JoinDecision.MATCH : bypass ? JoinDecision.BUILDER : JoinDecision.KICK;
    }

    /** Builders without an assignment come from /pvp build in the lobby and should land in pvp_build directly. */
    public static boolean sendToBuildWorld(JoinDecision decision) { return decision == JoinDecision.BUILDER; }

    public static boolean mayBypass(Predicate<String> hasPermission) {
        return hasPermission.test("craftcore.build.pvp") || hasPermission.test("group.builder") || hasPermission.test("group.admin");
    }

    public static GameMode joinMode(boolean builder, boolean assigned) {
        return builder && !assigned ? GameMode.CREATIVE : GameMode.ADVENTURE;
    }
}
