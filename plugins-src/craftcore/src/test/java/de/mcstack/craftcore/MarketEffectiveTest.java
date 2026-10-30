package de.mcstack.craftcore;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketEffectiveTest {
  static PriceCatalog.CatalogRecipe recipe(Material output, int amount, Material ingredient, int times) {
    return new PriceCatalog.CatalogRecipe(output, amount, Collections.nCopies(times, List.of(ingredient)));
  }
  static Map<Material, MarketEffective.Quote> sample() {
    var cfg = new YamlConfiguration();
    cfg.set("base.WHEAT", 0.5);
    cfg.set("base.IRON_INGOT", 8.0);
    cfg.set("base.IRON_BLOCK", 72.0);
    cfg.set("base.DIAMOND", 100.0);
    cfg.set("base.TOTEM_OF_UNDYING", 533.0);
    cfg.set("base.OAK_LOG", 1.0);
    var recipes = List.of(
      recipe(Material.BREAD, 1, Material.WHEAT, 3),
      recipe(Material.IRON_BLOCK, 1, Material.IRON_INGOT, 9),
      recipe(Material.IRON_INGOT, 1, Material.RAW_IRON, 1),
      recipe(Material.IRON_INGOT, 1, Material.IRON_ORE, 1),
      recipe(Material.IRON_INGOT, 1, Material.DEEPSLATE_IRON_ORE, 1),
      recipe(Material.DIAMOND_BLOCK, 1, Material.DIAMOND, 9),
      recipe(Material.OAK_PLANKS, 4, Material.OAK_LOG, 1),
      recipe(Material.STICK, 4, Material.OAK_PLANKS, 2));
    return MarketEffective.quote(MarketEffective.bases(cfg), recipes);
  }
  @Test void bankBuysBaseItemsAtHalf() {
    var wheat = sample().get(Material.WHEAT);
    assertEquals(0.5, wheat.buy(), 1e-9);
    assertEquals(0.25, wheat.sell(), 1e-9);
    assertEquals(0.5, wheat.base(), 1e-9);
  }
  @Test void recipesCostIngredientsMinusTenPercent() {
    var bread = sample().get(Material.BREAD);
    assertEquals(1.35, bread.buy(), 1e-9);
    assertEquals(0.675, bread.sell(), 1e-9);
    var stick = sample().get(Material.STICK);
    assertEquals(0.10125, stick.buy(), 1e-9);
    assertEquals(0.050625, stick.sell(), 1e-9);
  }
  @Test void explicitBaseIsNotUndercutByItsOwnRecipe() {
    var block = sample().get(Material.IRON_BLOCK);
    assertEquals(72.0, block.buy(), 1e-9);
    assertEquals(36.0, block.sell(), 1e-9);
  }
  @Test void oresSellForNinetyPercentOfSmeltedResultAndBankDoesNotSellThem() {
    var quotes = sample();
    for (Material ore : List.of(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.RAW_IRON)) {
      var row = quotes.get(ore);
      assertNotNull(row, ore.name());
      assertNull(row.buy(), ore.name());
      assertEquals(3.6, row.sell(), 1e-9, ore.name());
    }
    assertFalse(MarketEffective.bankSells(Material.RAW_IRON_BLOCK));
    assertNull(quotes.get(Material.RAW_IRON_BLOCK));
    var chained = MarketEffective.quote(Map.of(Material.IRON_INGOT, 8.0), List.of(
      recipe(Material.RAW_IRON, 1, Material.IRON_ORE, 1),
      recipe(Material.IRON_INGOT, 1, Material.RAW_IRON, 1)));
    assertEquals(3.6, chained.get(Material.RAW_IRON).sell(), 1e-9);
    assertEquals(3.24, chained.get(Material.IRON_ORE).sell(), 1e-9);
    assertNull(chained.get(Material.IRON_ORE).buy());
  }
  @Test void bankNeverSellsDiamondsDiamondBlocksOrTotems() {
    var quotes = sample();
    var diamond = quotes.get(Material.DIAMOND);
    var block = quotes.get(Material.DIAMOND_BLOCK);
    var totem = quotes.get(Material.TOTEM_OF_UNDYING);
    assertNull(diamond.buy());
    assertEquals(50.0, diamond.sell(), 1e-9);
    assertNull(block.buy());
    assertEquals(810.0, block.base(), 1e-9);
    assertEquals(405.0, block.sell(), 1e-9);
    assertNull(totem.buy());
    assertEquals(266.5, totem.sell(), 1e-9);
  }
  @Test void lockFallbackParsesBuyAndSell() {
    var cfg = new YamlConfiguration();
    cfg.set("market.lock-fallback.STONE.buy", true);
    cfg.set("market.lock-fallback.WHEAT.sell", true);
    cfg.set("market.lock-fallback.SAND", true);
    var locks = MarketEffective.lockFallback(cfg);
    assertEquals(new MarketEffective.Lock(true, false), locks.get(Material.STONE));
    assertEquals(new MarketEffective.Lock(false, true), locks.get(Material.WHEAT));
    assertEquals(new MarketEffective.Lock(true, false), locks.get(Material.SAND));
    assertTrue(locks.get(Material.STONE).or(new MarketEffective.Lock(false, true)).sell());
  }
}
