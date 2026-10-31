package de.mcstack.craftcore.pvp;

import java.util.function.Predicate;

public final class PvpAccess {
    private PvpAccess() {}

    public static boolean mayBypass(Predicate<String> hasPermission) {
        return hasPermission.test("craftcore.build.pvp") || hasPermission.test("group.builder") || hasPermission.test("group.admin");
    }
}
