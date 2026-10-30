package de.mcstack.craftcore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Prueft prices-base.yml gegen alle Rezepte aus recipes-26.1.tsv (minecraft-data 26.1 + Schmelzen/Schmieden). */
class RecipeArbitrageTest {
  record R(Material out, int count, String kind, String cls, List<List<Material>> slots) {
    boolean selfCopy() { return slots.stream().anyMatch(s -> s.contains(out)); }
  }
  static final double EPS = 1e-5;
  static List<R> recipes() throws IOException {
    List<R> out = new ArrayList<>();
    try (var in = new BufferedReader(new InputStreamReader(Objects.requireNonNull(RecipeArbitrageTest.class.getResourceAsStream("/recipes-26.1.tsv")), StandardCharsets.UTF_8))) {
      for (String line; (line = in.readLine()) != null; ) {
        if (line.startsWith("#") || line.isBlank()) continue;
        String[] f = line.split("\t");
        List<List<Material>> slots = new ArrayList<>();
        for (String slot : f[4].split(";")) { List<Material> o = new ArrayList<>(); for (String n : slot.split("\\|")) o.add(mat(n)); slots.add(o); }
        out.add(new R(mat(f[0]), Integer.parseInt(f[1]), f[2], f[3], slots));
      }
    }
    return out;
  }
  static Material mat(String n) { Material m = Material.matchMaterial(n); assertNotNull(m, n); return m; }
  static YamlConfiguration prices() { return ArbitrageTest.load("prices-base.yml"); }
  /** Echte config.yml aus den Plugin-Ressourcen, ohne Overrides. */
  static YamlConfiguration liveCfg() { return ArbitrageTest.load("config.yml"); }
  static double p(YamlConfiguration prices, Material m) { assertTrue(prices.isSet("base." + m.name()), "kein expliziter Preis: " + m); return prices.getDouble("base." + m.name()); }
  static double cheapest(YamlConfiguration prices, List<Material> options) { return options.stream().mapToDouble(m -> p(prices, m)).min().orElseThrow(); }
  static long buy(YamlConfiguration cfg, YamlConfiguration prices, Material m) { return Market.cents(cfg, m, Market.quote(cfg, m, p(prices, m)).base(), true); }
  static long sell(YamlConfiguration cfg, YamlConfiguration prices, Material m) { return BuybackCaps.capCents(cfg, m, Market.cents(cfg, m, Market.quote(cfg, m, p(prices, m)).base(), false)); }

  @Test void everyRecipeItemHasExplicitPrice() throws IOException {
    var prices = prices(); List<String> missing = new ArrayList<>();
    for (R r : recipes()) { if (!prices.isSet("base." + r.out().name())) missing.add(r.out().name()); for (var s : r.slots()) for (Material m : s) if (!prices.isSet("base." + m.name())) missing.add(m.name()); }
    assertTrue(missing.isEmpty(), "Fallback statt Preis: " + new TreeSet<>(missing));
  }
  /** Kein Produkt liegt unter seinen Zutaten (guenstigstes Rezept; Schmelzen x1.3). Umfaerben/Zerlegen sind Zyklen und ausgenommen. */
  @Test void noProductBelowItsIngredients() throws IOException {
    var prices = prices(); Map<Material, Double> lower = new EnumMap<>(Material.class);
    for (R r : recipes()) {
      if (!r.cls().equals("normal")) continue;
      double v;
      if (r.selfCopy()) {
        long n = r.slots().stream().filter(s -> s.contains(r.out())).count(); double other = 0;
        for (var s : r.slots()) if (!s.contains(r.out())) other += cheapest(prices, s);
        v = other / (r.count() - n);
      } else {
        double sum = 0; for (var s : r.slots()) sum += cheapest(prices, s);
        v = sum * (r.kind().equals("smelt") ? 1.3 : 1.0) / r.count();
      }
      lower.merge(r.out(), v, Math::min);
    }
    List<String> breaks = new ArrayList<>();
    lower.forEach((m, v) -> { if (p(prices, m) < v * (1 - EPS)) breaks.add(m + " " + p(prices, m) + " < " + v); });
    assertTrue(breaks.isEmpty(), String.join("\n", breaks));
    for (String s : List.of("STICK", "NETHERITE_SCRAP", "NETHERITE_UPGRADE_SMITHING_TEMPLATE", "SHULKER_BOX", "ENCHANTING_TABLE")) assertTrue(lower.containsKey(mat(s)), s);
  }
  /** Bank-Ankauf des Produkts liegt nie ueber den Bank-Kaufkosten der Zutaten (alle Rezepte, Live-Faktoren). */
  @Test void bankSaleNeverAboveRecipeCost() throws IOException {
    var cfg = liveCfg(); var prices = prices(); List<String> profit = new ArrayList<>();
    for (R r : recipes()) {
      if (r.selfCopy() || cfg.getStringList("market.unsellable").contains(r.out().name())) continue;
      long cost = 0; boolean buyable = true;
      for (var s : r.slots()) {
        long best = Long.MAX_VALUE;
        for (Material m : s) if (!Market.unbuyable(cfg, m)) best = Math.min(best, buy(cfg, prices, m));
        if (best == Long.MAX_VALUE) { buyable = false; break; }
        cost += best;
      }
      if (!buyable) continue;
      long revenue = r.count() * sell(cfg, prices, r.out());
      // Toleranz: Abrunden auf ganze Cent je Zutat
      if (revenue > cost + r.slots().size() + r.count()) profit.add(r.out() + " x" + r.count() + " revenue=" + revenue + " cost=" + cost + " " + r.slots());
    }
    assertTrue(profit.isEmpty(), profit.size() + " Arbitrage-Rezepte:\n" + String.join("\n", profit.subList(0, Math.min(30, profit.size()))));
  }
  @Test void bankBuysAtHalfThePurchasePrice() {
    var cfg = liveCfg(); var prices = prices();
    for (String n : List.of("OAK_LOG", "IRON_INGOT", "COPPER_INGOT", "BREAD", "CHEST", "DIAMOND", "ENDER_PEARL")) {
      Material m = mat(n); double ratio = (double) sell(cfg, prices, m) / buy(cfg, prices, m);
      assertEquals(0.5, ratio, 0.05, n);
    }
  }
  @Test void copperToIronRatioAndTiers() {
    var cfg = liveCfg(); var prices = prices();
    // Wie viele Kupferbarren muss ein Spieler an die Bank verkaufen, um 1 Eisenbarren zu kaufen?
    double ratio = (double) buy(cfg, prices, Material.IRON_INGOT) / sell(cfg, prices, Material.COPPER_INGOT);
    assertTrue(ratio >= 8 && ratio <= 10, "Kupferbarren je Eisenbarren: " + ratio);
    List<String> tiers = List.of("DIRT", "COPPER_INGOT", "IRON_INGOT", "GOLD_INGOT", "DIAMOND", "NETHERITE_INGOT");
    for (int i = 1; i < tiers.size(); i++) assertTrue(p(prices, mat(tiers.get(i - 1))) < p(prices, mat(tiers.get(i))), tiers.get(i));
    for (String n : List.of("SHULKER_BOX", "ELYTRA", "BEACON", "DRAGON_EGG", "DRAGON_HEAD", "HEAVY_CORE", "ENCHANTING_TABLE", "TOTEM_OF_UNDYING", "NETHER_STAR", "SHULKER_SHELL"))
      assertTrue(p(prices, mat(n)) >= 400, n);
  }
  @Test void coloredShulkerBoxesCostBoxPlusDye() {
    var prices = prices(); int n = 0;
    for (Material m : Material.values()) if (m.name().endsWith("_SHULKER_BOX") && !m.isLegacy()) {
      Material dye = mat(m.name().replace("_SHULKER_BOX", "_DYE"));
      assertEquals(p(prices, Material.SHULKER_BOX) + p(prices, dye), p(prices, m), 1e-5, m.name()); n++;
    }
    assertEquals(16, n);
  }
  @Test void valuableItemsNoLongerBelowTenCp() {
    var cfg = liveCfg(); var prices = prices();
    for (String n : List.of("ENDER_PEARL", "SPONGE", "CHORUS_FRUIT", "POPPED_CHORUS_FRUIT", "PURPUR_BLOCK", "EXPERIENCE_BOTTLE", "WET_SPONGE", "DRAGON_EGG", "DRAGON_HEAD", "HEAVY_CORE", "ENCHANTING_TABLE", "NETHERITE_UPGRADE_SMITHING_TEMPLATE", "SADDLE", "HEART_OF_THE_SEA", "TRIDENT", "ECHO_SHARD", "TRIAL_KEY", "OMINOUS_TRIAL_KEY", "WITHER_SKELETON_SKULL", "SNIFFER_EGG", "MACE", "BREEZE_ROD", "NETHERITE_HORSE_ARMOR"))
      assertTrue(buy(cfg, prices, mat(n)) >= 1000, n + " Kauf " + buy(cfg, prices, mat(n)) + " ct");
  }
  @Test void realValuesCopperShulkerDiamond() {
    var cfg = liveCfg(); var prices = prices();
    assertEquals(0.75, cfg.getDouble("market.sell-factor")); assertEquals(0.5, cfg.getDouble("market.max-sell-ratio"));
    double copper = (double) buy(cfg, prices, Material.IRON_INGOT) / sell(cfg, prices, Material.COPPER_INGOT);
    assertTrue(copper >= 8 && copper <= 10, "Kupfer je Eisen " + copper);
    assertEquals(30000, sell(cfg, prices, Material.SHULKER_BOX), 1500);
    assertEquals(15000, sell(cfg, prices, Material.DIAMOND), 750);
  }
  @Test void effectiveFromIsTopLevelIso8601() {
    var prices = prices();
    assertTrue(prices.isString("effective_from"));
    assertNotNull(PriceMeta.effectiveFrom(prices));
  }
}
