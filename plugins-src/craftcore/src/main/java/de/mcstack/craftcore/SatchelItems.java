package de.mcstack.craftcore;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

final class SatchelItems {
  static final NamespacedKey CAP = new NamespacedKey("craftcore", "satchel_cap");

  private SatchelItems() {}

  static void stamp(ItemStack item, long capCents) {
    if (capCents < 0) throw new IllegalArgumentException("cap");
    ItemMeta meta = item.getItemMeta();
    meta.getPersistentDataContainer().set(CAP, PersistentDataType.LONG, capCents);
    item.setItemMeta(meta);
  }

  static Long cap(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return null;
    return item.getItemMeta().getPersistentDataContainer().get(CAP, PersistentDataType.LONG);
  }

  static boolean tagged(ItemStack item) { return cap(item) != null; }
}
