package de.mcstack.craftcore.pvp;

import java.util.function.Predicate;
import org.bukkit.GameMode;

public final class PvpAccess {
    private PvpAccess() {}
    public enum JoinDecision { MATCH, BUILDER, KICK }
    public static JoinDecision decide(boolean hasAssignment, boolean bypass) {
        return hasAssignment ? JoinDecision.MATCH : bypass ? JoinDecision.BUILDER : JoinDecision.KICK;
    }
    /** A lobby /pvp build request lands in pvp_build even if an old assignment is still open. */
    public static JoinDecision destination(boolean buildRequest, boolean hasAssignment, boolean bypass) {
        return buildRequest ? JoinDecision.BUILDER : decide(hasAssignment, bypass);
    }

    /** Builders without an assignment come from /pvp build in the lobby and should land in pvp_build directly. */
    public static boolean sendToBuildWorld(JoinDecision decision) { return decision == JoinDecision.BUILDER; }

    public static boolean mayBypass(Predicate<String> hasPermission) {
        return hasPermission.test("craftcore.build.pvp") || hasPermission.test("group.builder") || hasPermission.test("group.admin");
    }

    public static GameMode joinMode(boolean builder, boolean assigned) {
        return builder && !assigned ? GameMode.CREATIVE : GameMode.ADVENTURE;
    }

    /** /pvp leave during a match would strand the opponent until the 5 minute draw; the match must be played out. */
    public static boolean mayLeave(boolean inMatch) { return !inMatch; }

    /** A teleport out of the match world is refused until the match finishes; the cleanup teleports after that. */
    public static boolean blocksTeleport(String matchWorld, boolean finishing, String fromWorld, String toWorld) {
        return matchWorld != null && !finishing && matchWorld.equals(fromWorld) && !matchWorld.equals(toWorld);
    }
}
