package de.mcstack.craftcore.vip;

/**
 * Pure rules for the VIP menu nether star. On VIP servers the menu is opened with {@code /vw} and
 * the star is no longer handed out; since the VIP inventories are separate from every other
 * server, a leftover item is left alone and only has to be non-duplicable, non-droppable and
 * unsellable.
 *
 * <p>Kept free of Bukkit types so every rule is unit-testable; {@link VipStarGuard} applies
 * them to the inventory and death events.</p>
 */
public final class VipStarRules {
    /**
     * PersistentDataContainer key of the menu star, namespace {@code craftcore}. Must not change:
     * stars from older builds are only recognisable through this exact key.
     */
    public static final String KEY = "vip_menu_star";

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

    /** Bank, Auktionshaus, Shop and Handel never buy the menu star. */
    public static boolean sellable(boolean tagged) { return !tagged; }

    /**
     * Refusal shown to the player when a menu star is offered to a trade. Matches the wording the
     * market uses for the same {@link #sellable} rule so the message is identical everywhere.
     */
    public static final String UNSELLABLE_MESSAGE = "Nicht handelbar";

    /**
     * Player-facing refusal for an item that may not be traded: the shared wording when
     * {@code tagged}, otherwise {@code null}. Single code path so auction, shop and trade
     * refuse the menu star with exactly one rule and message.
     */
    public static String refusal(boolean tagged) { return tagged ? UNSELLABLE_MESSAGE : null; }
}
