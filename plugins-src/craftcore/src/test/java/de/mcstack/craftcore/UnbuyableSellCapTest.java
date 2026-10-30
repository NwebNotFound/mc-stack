package de.mcstack.craftcore;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Bank-Ankauf hoechstens 50 % des (rechnerischen) Kaufpreises, auch fuer nicht kaufbare Items. */
class UnbuyableSellCapTest {
  static final List<Material> UNBUYABLE = List.of(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK, Material.NETHERITE_SCRAP,
    Material.NETHERITE_CHESTPLATE, Material.NETHERITE_HORSE_ARMOR, Material.SHULKER_BOX, Material.RED_SHULKER_BOX, Material.ENCHANTED_BOOK);
  @Test void unbuyableItemsAreCappedAtHalfLikeEveryOtherItem() {
    var cfg = RecipeArbitrageTest.liveCfg(); var prices = RecipeArbitrageTest.prices();
    cfg.set("market.sell-factor", 1.5); // ungedeckelt waere Ankauf = Kaufpreis
    for (Material m : UNBUYABLE) {
      assertTrue(Market.unbuyable(cfg, m), m.name());
      double base = Market.quote(cfg, m, prices.getDouble("base." + m.name())).base();
      long buy = Market.cents(cfg, m, base, true), sell = Market.cents(cfg, m, base, false);
      assertTrue(sell <= buy / 2, m + " sell=" + sell + " buy=" + buy);
      assertEquals(buy / 2, sell, 1, m.name());
    }
  }
  @Test void liveFactorsGiveHalfForAllItemsIncludingLockedAndUnbuyable() {
    var cfg = RecipeArbitrageTest.liveCfg(); var prices = RecipeArbitrageTest.prices();
    for (String key : prices.getConfigurationSection("base").getKeys(false)) {
      Material m = Material.matchMaterial(key);
      double base = Market.quote(cfg, m, prices.getDouble("base." + key)).base();
      long buy = Market.cents(cfg, m, base, true), sell = Market.cents(cfg, m, base, false);
      assertTrue(sell * 2 <= buy + 1, key + " sell=" + sell + " buy=" + buy);
    }
  }
}
