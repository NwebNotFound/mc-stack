package de.mcstack.craftcore.pvp;

import java.util.function.Predicate;

public final class PvpAccess {
    private PvpAccess() {}
    public enum JoinDecision { MATCH, BUILDER, KICK }
    public static JoinDecision decide(boolean hasAssignment, boolean bypass) {
        return hasAssignment ? JoinDecision.MATCH : bypass ? JoinDecision.BUILDER : JoinDecision.KICK;
    }

    public static boolean mayBypass(Predicate<String> hasPermission) {
        return hasPermission.test("craftcore.build.pvp") || hasPermission.test("group.builder") || hasPermission.test("group.admin");
    }
}
