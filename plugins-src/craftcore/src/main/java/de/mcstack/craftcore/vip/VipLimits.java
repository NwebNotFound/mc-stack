package de.mcstack.craftcore.vip;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Rank to world slots and to the player cap of one running world.
 * Pure so the rules stay unit-testable without a server.
 */
public final class VipLimits {
    public static final int MAX_PLAYERS = 15;
    public static final int MAX_SLOTS = 3;
    public static final int DEFAULT_VIP_PLAYERS = 5;
    public static final int DEFAULT_MVP_PLAYERS = 10;
    public static final int DEFAULT_ADMIN_PLAYERS = 10;
    public static final int DEFAULT_ADMIN_SLOTS = 3;

    private VipLimits() {}

    /** Config values above the hard cap are clamped, so no config can exceed 15 players. */
    public static int clampPlayers(int configured) {
        return Math.max(1, Math.min(MAX_PLAYERS, configured));
    }

    public static int playerLimit(String rank, int vip, int mvp, int admin) {
        int configured = switch (rank == null ? "" : rank.toLowerCase(Locale.ROOT)) {
            case "admin" -> admin;
            case "mvp" -> mvp;
            case "vip" -> vip;
            default -> 0;
        };
        return configured <= 0 ? 0 : clampPlayers(configured);
    }

    /** Admin slots are a floor, never a downgrade of meta slots. */
    public static int slots(String meta, String fallback, boolean admin, int adminSlots) {
        int resolved = Math.max(parse(meta), parse(fallback));
        if (admin) resolved = Math.max(resolved, Math.max(0, adminSlots));
        return Math.min(resolved, MAX_SLOTS);
    }

    public static int parse(String value) {
        try { return Math.max(0, Integer.parseInt(value == null ? "" : value.trim())); }
        catch (RuntimeException e) { return 0; }
    }

    /** Highest rank wins: admin, then mvp, then vip, otherwise an empty string. */
    public static String rank(Collection<String> groups) {
        if (groups == null) return "";
        List<String> names = groups.stream().filter(g -> g != null).map(g -> g.toLowerCase(Locale.ROOT)).toList();
        if (names.contains("admin")) return "admin";
        if (names.contains("mvp")) return "mvp";
        if (names.contains("vip")) return "vip";
        return "";
    }

    /** Owner and admins may enter a world that already reached its player cap. */
    public static boolean mayJoinFull(boolean owner, boolean admin) { return owner || admin; }

    public static boolean atCap(int online, int limit) { return limit > 0 && online >= limit; }
}
