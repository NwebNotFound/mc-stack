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

    /** What the applier has to do with one player's game mode. */
    public record Mode(boolean change, GameMode target, boolean mark, boolean clear) {
        public static Mode none() { return new Mode(false, null, false, false); }
    }

    /**
     * Game mode decision for one player, used on join and by the five-second refresh.
     *
     * <p>The creative addon grants creative <em>once</em> and records it (the applier keeps the mark
     * in the player data container, so a restart does not lose it). A marked player keeps every mode
     * they pick afterwards, which is what makes {@code /gamemode} work while the addon is active.
     * With the addon switched off, a marked player still in creative gets the mode from before the
     * grant back - also after a restart or a relog.</p>
     *
     * @param marked  the player was given creative by the addon
     * @param current game mode the player has right now
     * @param before  game mode the player had before the grant, may be null
     */
    public static Mode onJoin(boolean creativeAddon, boolean marked, GameMode current, GameMode before) {
        if (creativeAddon) {
            if (marked) return Mode.none();
            if (current == GameMode.CREATIVE || current == GameMode.SPECTATOR) return Mode.none();
            return new Mode(true, GameMode.CREATIVE, true, false);
        }
        if (!marked) return Mode.none();
        if (current != GameMode.CREATIVE) return new Mode(false, null, false, true);
        GameMode back = before == null || before == GameMode.CREATIVE ? GameMode.SURVIVAL : before;
        return new Mode(true, back, false, true);
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
