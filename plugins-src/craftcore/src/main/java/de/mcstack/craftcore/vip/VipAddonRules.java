package de.mcstack.craftcore.vip;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;

/**
 * Pure effects of the per-world addons. Kept free of the server so the game mode rollback and
 * the difficulty hand-back are unit-testable.
 */
public final class VipAddonRules {
    private VipAddonRules() {}

    /**
     * Game mode the addon leaves behind, {@code null} when nothing has to change.
     * Turning the creative addon off gives a player back survival instead of leaving them
     * flying around in creative.
     */
    public static GameMode mode(boolean creativeAddon, boolean grantedByAddon, GameMode current) {
        if (creativeAddon) return current == GameMode.CREATIVE ? null : GameMode.CREATIVE;
        if (grantedByAddon && current == GameMode.CREATIVE) return GameMode.SURVIVAL;
        return null;
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
