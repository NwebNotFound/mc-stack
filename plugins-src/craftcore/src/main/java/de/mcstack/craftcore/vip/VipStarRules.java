package de.mcstack.craftcore.vip;

/**
 * Pure rules for the VIP menu nether star. The star only opens the manage screen; it must
 * never leave the player's inventory, be duplicated, or be sold.
 *
 * <p>Kept free of Bukkit types so every rule is unit-testable; {@link VipStarGuard} applies
 * them to the inventory and death events.</p>
 */
public final class VipStarRules {
    /** PersistentDataContainer key of the menu star, namespace {@code craftcore}. */
    public static final String KEY = "vip_menu_star";
    /** Hotbar slot that receives the star on join; it is never overwritten. */
    public static final int HOTBAR_SLOT = 8;

    private VipStarRules() {}

    /** Dropping the star by hand (Q) is always blocked. */
    public static boolean mayDrop(boolean tagged) { return !tagged; }

    /**
     * Any inventory click that touches the star is cancelled: the clicked slot, the cursor,
     * or the hotbar slot of a number-key swap. That also covers containers and shulkers.
     */
    public static boolean blocksClick(boolean taggedSlot, boolean taggedCursor, boolean taggedHotbar) {
        return taggedSlot || taggedCursor || taggedHotbar;
    }

    /** Dragging the star (or dragging anything with the star on the cursor) is cancelled. */
    public static boolean blocksDrag(boolean taggedCursor, boolean taggedDragged) {
        return taggedCursor || taggedDragged;
    }

    /** Creative middle-click and clone would duplicate the star. */
    public static boolean blocksCreativeClone(boolean tagged, boolean creative) { return tagged && creative; }

    /** The star stays with the player on death and is removed from the drops. */
    public static boolean survivesDeath(boolean tagged) { return tagged; }

    /** Offhand swap (F) moves the star into another slot: blocked. */
    public static boolean maySwapHands(boolean taggedMain, boolean taggedOffhand) {
        return !taggedMain && !taggedOffhand;
    }

    /** Item frames, hoppers and every other transfer out of the inventory: blocked. */
    public static boolean mayTransfer(boolean tagged) { return !tagged; }

    /** Market, {@code /sell} and the bank never buy the menu star. */
    public static boolean sellable(boolean tagged) { return !tagged; }

    /** Join: the menu is a command now, so any leftover star is removed instead of handed out. */
    public static boolean stripOnJoin(boolean tagged) { return tagged; }
}
