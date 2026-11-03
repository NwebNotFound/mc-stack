package de.mcstack.craftcore;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Every executable recipe stays unprofitable at the curve corners. */
class RecipeCurveArbitrageTest {
  @Test void everyExecutableRecipe() throws Exception {
    YamlConfiguration cfg = RecipeArbitrageTest.liveCfg();
    YamlConfiguration prices = RecipeArbitrageTest.prices();
    CurvePrice.Factors factors = CurvePrice.factors(cfg);
    Set<String> unsellable = new HashSet<>(cfg.getStringList("market.unsellable"));
    int checked = 0, bare = 0;
    String bareExample = null;
    for (RecipeArbitrageTest.R recipe : RecipeArbitrageTest.recipes()) {
      if (recipe.count() <= 0 || recipe.selfCopy()) continue;
      if (!prices.isSet("base." + recipe.out().name()) || unsellable.contains(recipe.out().name())) continue;
      long cost = 0;
      boolean ok = true;
      for (List<Material> slot : recipe.slots()) {
        long best = Long.MAX_VALUE;
        for (Material option : slot) {
          if (!prices.isSet("base." + option.name()) || Market.unbuyable(cfg, option)) continue;
          best = Math.min(best, buyAt(cfg, prices, option, true, factors));
        }
        if (best == Long.MAX_VALUE) { ok = false; break; }
        cost += best;
      }
      if (!ok) continue;
      checked++;
      long cap = cost / recipe.count();
      double high = base(cfg, prices, recipe.out()) * 1.2;
      long capped = recipe.count() * CurvePrice.sellCents(high, cap, factors);
      long uncapped = recipe.count() * CurvePrice.sellCents(high, null, factors);
      if (uncapped > cost) {
        bare++;
        if (bareExample == null) bareExample = recipe.out().name();
      }
      assertTrue(capped <= cost, recipe.out() + " x" + recipe.count() + " " + capped + " > " + cost);
    }
    assertEquals(1655, checked);
    assertTrue(bare > 0, "Deckel ist wirkungslos");
    assertNotNull(bareExample);
  }

  @Test void netheriteChestIngredientsStayUnbuyable() {
    YamlConfiguration cfg = RecipeArbitrageTest.liveCfg();
    for (String name : List.of("DIAMOND_CHESTPLATE", "NETHERITE_INGOT", "NETHERITE_UPGRADE_SMITHING_TEMPLATE")) {
      Material material = Material.matchMaterial(name);
      assertNotNull(material, name);
      assertTrue(Market.unbuyable(cfg, material), name);
      assertTrue(cfg.getStringList("market.unbuyable").contains(name), name);
    }
  }

  @Test void rawGoldCornerNeedsTheCap() {
    YamlConfiguration cfg = RecipeArbitrageTest.liveCfg();
    YamlConfiguration prices = RecipeArbitrageTest.prices();
    CurvePrice.Factors factors = CurvePrice.factors(cfg);
    long block = buyAt(cfg, prices, Material.RAW_GOLD_BLOCK, true, factors);
    long each = CurvePrice.sellCents(base(cfg, prices, Material.RAW_GOLD) * 1.2, null, factors);
    assertTrue(9 * each > block, each + " * 9 vs " + block);
    assertTrue(9 * Math.min(each, block / 9) <= block);
  }

  private static double base(YamlConfiguration cfg, YamlConfiguration prices, Material material) {
    return Market.quote(cfg, material, prices.getDouble("base." + material.name())).base();
  }

  private static long buyAt(YamlConfiguration cfg, YamlConfiguration prices, Material material, boolean floor, CurvePrice.Factors factors) {
    double b = base(cfg, prices, material);
    return CurvePrice.buyCents(floor ? b * 0.25 : b, factors);
  }
}
