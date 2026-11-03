package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keeps the nether star of a VIP world in the player's inventory: no drop, no move into a
 * container, no death drop, no creative clone, no sale. The rules live in {@link VipStarRules} and
 * the refusal wording in {@link VipStarRules#UNSELLABLE_MESSAGE}.
 */
public final class VipStarGuard implements Listener {
    /** {@code craftcore:vip_menu_star}, the same key {@link VipWorldMenu} stamps on the star. */
    public static final NamespacedKey KEY = Objects.requireNonNull(
        NamespacedKey.fromString("craftcore:" + VipStarRules.KEY), "ungueltiger Star-Key");

    /** True when the item carries the menu-star tag. */
    public static boolean tagged(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    /** Market, {@code /sell} and the bank must not buy the menu star. */
    public static boolean sellable(ItemStack item) {
        return VipStarRules.sellable(tagged(item));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void drop(PlayerDropItemEvent event) {
        if (!VipStarRules.mayDrop(tagged(event.getItemDrop().getItemStack()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack hotbar = event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
        if (VipStarRules.blocksClick(tagged(event.getCurrentItem()), tagged(event.getCursor()), tagged(hotbar))) {
            event.setCancelled(true);
        }
    }

    /** Middle-click in creative clones an item; the event has its own handler list. */
    @EventHandler(priority = EventPriority.HIGH)
    public void clone(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!VipStarRules.blocksCreativeClone(tagged(event.getCurrentItem()) || tagged(event.getCursor()), true)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void drag(InventoryDragEvent event) {
        boolean dragged = event.getNewItems().values().stream().anyMatch(VipStarGuard::tagged);
        if (VipStarRules.blocksDrag(tagged(event.getOldCursor()), dragged)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void swapHands(PlayerSwapHandItemsEvent event) {
        if (!VipStarRules.maySwapHands(tagged(event.getMainHandItem()), tagged(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void death(PlayerDeathEvent event) {
        event.getDrops().removeIf(VipStarGuard::tagged);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void moveItem(InventoryMoveItemEvent event) {
        if (!VipStarRules.mayTransfer(tagged(event.getItem()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void frame(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof ItemFrame)) return;
        if (!VipStarRules.mayTransfer(tagged(event.getPlayer().getInventory().getItemInMainHand()))) {
            event.setCancelled(true);
        }
    }

    /**
     * Clears leftover menu stars from a player's inventory. The menu is a command now, so the
     * hotbar slot stays free and the item no longer blocks a slot forever.
     */
    public static void remove(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (VipStarRules.stripOnJoin(tagged(item))) player.getInventory().setItem(slot, null);
        }
    }
}
