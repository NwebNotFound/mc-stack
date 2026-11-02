package de.mcstack.craftcore.vip;

import java.util.Collection;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;

/**
 * Pure effects of the per-world addons. Kept free of the server so every addon effect, the game
 * mode hand-back and the difficulty hand-back are unit-testable.
 */
public final class VipAddonRules {
    /** World settings one addon set produces. */
    public record Effects(boolean keepInventory, boolean daylightCycle, boolean mobSpawning,
                          boolean pvp, boolean pinNoon) {}

    private VipAddonRules() {}

    /** Every world rule an addon set switches; one entry per catalog effect. */
    public static Effects world(Collection<String> addons) {
        return new Effects(
            has(addons, "keep-inventory"),
            !has(addons, "day-lock"),
            !has(addons, "peaceful"),
            has(addons, "pvp"),
            has(addons, "day-lock"));
    }

    private static boolean has(Collection<String> addons, String id) {
        return addons != null && addons.contains(id);
    }

    /**
     * Game mode the creative addon grants when it is switched on. It is granted once per player:
     * an already granted player keeps the mode they pick, so {@code /gamemode} keeps working while
     * the addon is active. Spectator and creative are left alone.
     */
    public static GameMode grant(boolean creativeAddon, boolean alreadyGranted, GameMode current) {
        if (!creativeAddon || alreadyGranted || current == null) return null;
        return current == GameMode.CREATIVE || current == GameMode.SPECTATOR ? null : GameMode.CREATIVE;
    }

    /**
     * Game mode given back when the creative addon is switched off. Only modes the addon handed out
     * are touched, and the mode from before the grant comes back.
     */
    public static GameMode revoke(boolean grantedByAddon, GameMode current, GameMode before) {
        if (!grantedByAddon || current != GameMode.CREATIVE) return null;
        return before == null || before == GameMode.CREATIVE ? GameMode.SURVIVAL : before;
    }

    /**
     * The peaceful addon owns the difficulty while it is on. When it is switched off the
     * previous (possibly admin chosen) difficulty comes back instead of always NORMAL.
     */
    public static Difficulty difficulty(boolean peaceful, Difficulty previous, Difficulty current) {
        if (peaceful) return Difficulty.PEACEFUL;
        if (current != Difficulty.PEACEFUL) return current;
        return previous == null ? Difficulty.NORMAL : previous;
    }
}
