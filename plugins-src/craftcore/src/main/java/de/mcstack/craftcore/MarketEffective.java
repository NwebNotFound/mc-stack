package de.mcstack.craftcore;

import java.io.File;
import java.sql.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Read model {@code market_effective}. Explicit base prices stay put. Other recipe outputs cost
 * the cheapest ingredients minus 10%. The bank buys at 50% of that cost, except ores, which sell
 * for 90% of the bank's purchase price of their smelted result. The bank never sells ores, diamonds,
 * diamond blocks or totems. A price is locked when {@code market_locks} or {@code market.lock-fallback} says so.
 */
final class MarketEffective {
  static final double BANK_BUY_RATE = 0.5;
  static final double RECIPE_FACTOR = 0.9;
  static final double ORE_OF_SMELTED = 0.9;
  record Lock(boolean buy, boolean sell) {
    Lock or(Lock other) { return new Lock(buy || other.buy, sell || other.sell); }
  }
  record Quote(Double buy, double sell, double base) {}
  private static final String VIEW = "CREATE OR REPLACE VIEW market_effective AS SELECT p.item AS item,"
    + "CASE WHEN COALESCE(l.lock_buy,FALSE) OR COALESCE(f.lock_buy,FALSE) THEN NULL ELSE p.buy_price END AS buy_price,"
    + "p.sell_price AS sell_price,"
    + "p.base_price AS base_price,"
    + "(p.buy_price IS NOT NULL AND NOT (COALESCE(l.lock_buy,FALSE) OR COALESCE(f.lock_buy,FALSE))) AS buy_enabled,"
    + "(p.sell_price IS NOT NULL) AS sell_enabled "
    + "FROM market_effective_price p "
    + "LEFT JOIN market_locks l ON l.item=p.item "
    + "LEFT JOIN market_lock_fallback f ON f.item=p.item";

  static boolean ore(Material material) {
    String name = material.name();
    return name.endsWith("_ORE") || name.startsWith("RAW_");
  }
  static boolean bankSells(Material material) {
    return !ore(material) && material != Material.DIAMOND && material != Material.DIAMOND_BLOCK && material != Material.TOTEM_OF_UNDYING;
  }
  static Map<Material, Double> bases(CraftCore core) {
    return bases(YamlConfiguration.loadConfiguration(new File(core.getDataFolder(), "prices-base.yml")));
  }
  static Map<Material, Double> bases(Configuration cfg) {
    Map<Material, Double> out = new EnumMap<>(Material.class);
    ConfigurationSection section = cfg.getConfigurationSection("base");
    if (section == null) return out;
    for (String key : section.getKeys(false)) {
      Material material = Material.matchMaterial(key);
      double value = section.getDouble(key);
      if (material == null || !Double.isFinite(value) || value < 0.01) throw new IllegalArgumentException("Ungueltiger Basispreis: " + key);
      out.put(material, value);
    }
    return out;
  }
  /** {@code market.lock-fallback.<ITEM>.buy/sell}. A bare true locks the bank sale (buy). */
  static Map<Material, Lock> lockFallback(Configuration cfg) {
    Map<Material, Lock> out = new EnumMap<>(Material.class);
    ConfigurationSection section = cfg.getConfigurationSection("market.lock-fallback");
    if (section == null) return out;
    for (String key : section.getKeys(false)) {
      Material material = Material.matchMaterial(key);
      if (material == null) continue;
      ConfigurationSection child = section.getConfigurationSection(key);
      boolean buy = child == null ? section.getBoolean(key) : child.getBoolean("buy");
      boolean sell = child != null && child.getBoolean("sell");
      if (buy || sell) out.put(material, new Lock(buy, sell));
    }
    return out;
  }
  static Map<Material, Material> smelts(List<PriceCatalog.CatalogRecipe> recipes) {
    Map<Material, Material> out = new EnumMap<>(Material.class);
    for (PriceCatalog.CatalogRecipe recipe : recipes) {
      if (recipe.amount() != 1 || recipe.ingredients().size() != 1 || recipe.ingredients().get(0).size() != 1) continue;
      Material input = recipe.ingredients().get(0).get(0);
      if (ore(input)) out.put(input, recipe.output());
    }
    return out;
  }
  static Map<Material, Quote> quote(Map<Material, Double> explicit, List<PriceCatalog.CatalogRecipe> recipes) {
    Map<Material, Material> smelts = smelts(recipes);
    Map<Material, Double> cost = new EnumMap<>(Material.class);
    cost.putAll(explicit);
    Set<Material> fixed = EnumSet.noneOf(Material.class);
    fixed.addAll(explicit.keySet());
    for (int pass = 0; pass < 64; pass++) {
      boolean changed = false;
      for (PriceCatalog.CatalogRecipe recipe : recipes) {
        if (recipe.amount() <= 0 || recipe.ingredients().isEmpty() || fixed.contains(recipe.output()) || smelt(recipe, smelts)) continue;
        double sum = 0;
        boolean known = true;
        for (List<Material> options : recipe.ingredients()) {
          double best = Double.POSITIVE_INFINITY;
          for (Material option : options) if (cost.containsKey(option)) best = Math.min(best, cost.get(option));
          if (!Double.isFinite(best)) { known = false; break; }
          sum += best;
        }
        if (!known) continue;
        double value = sum * RECIPE_FACTOR / recipe.amount();
        if (value < cost.getOrDefault(recipe.output(), Double.POSITIVE_INFINITY) - 1e-12) {
          cost.put(recipe.output(), value);
          changed = true;
        }
      }
      if (!changed) break;
    }
    Map<Material, Quote> out = new EnumMap<>(Material.class);
    for (var entry : cost.entrySet()) {
      double base = entry.getValue();
      Double buy = bankSells(entry.getKey()) ? base : null;
      out.put(entry.getKey(), new Quote(buy, base * BANK_BUY_RATE, base));
    }
    for (int pass = 0; pass < smelts.size() + 1; pass++) {
      boolean changed = false;
      for (var entry : smelts.entrySet()) {
        Quote result = out.get(entry.getValue());
        if (result == null) continue;
        double sell = result.sell() * ORE_OF_SMELTED;
        Quote existing = out.get(entry.getKey());
        double base = existing == null ? result.base() : existing.base();
        if (existing != null && existing.buy() == null && existing.sell() == sell && existing.base() == base) continue;
        out.put(entry.getKey(), new Quote(null, sell, base));
        changed = true;
      }
      if (!changed) break;
    }
    for (Material material : List.copyOf(out.keySet())) {
      if (!bankSells(material)) {
        Quote quote = out.get(material);
        out.put(material, new Quote(null, quote.sell(), quote.base()));
      }
    }
    return out;
  }
  private static boolean smelt(PriceCatalog.CatalogRecipe recipe, Map<Material, Material> smelts) {
    if (recipe.amount() != 1 || recipe.ingredients().size() != 1 || recipe.ingredients().get(0).size() != 1) return false;
    return recipe.output() == smelts.get(recipe.ingredients().get(0).get(0));
  }
  static void ensureSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) { ensureSchema(statement); }
  }
  static void ensureSchema(Statement statement) throws SQLException {
    statement.execute("CREATE TABLE IF NOT EXISTS market_locks (item VARCHAR(64) PRIMARY KEY, lock_buy BOOLEAN NOT NULL DEFAULT FALSE, lock_sell BOOLEAN NOT NULL DEFAULT FALSE, reason VARCHAR(128), updated_at BIGINT NOT NULL DEFAULT 0)");
    statement.execute("CREATE TABLE IF NOT EXISTS market_lock_fallback (item VARCHAR(64) PRIMARY KEY, lock_buy BOOLEAN NOT NULL DEFAULT FALSE, lock_sell BOOLEAN NOT NULL DEFAULT FALSE)");
    statement.execute("CREATE TABLE IF NOT EXISTS market_effective_price (item VARCHAR(64) PRIMARY KEY, buy_price DECIMAL(24,8) NULL, sell_price DECIMAL(24,8) NULL, base_price DECIMAL(24,8) NOT NULL)");
    statement.execute(VIEW);
  }
  static void refresh(Connection connection, Configuration cfg, Map<Material, Double> explicit, List<PriceCatalog.CatalogRecipe> recipes) throws SQLException {
    ensureSchema(connection);
    Map<Material, Quote> quotes = quote(explicit, recipes);
    Map<Material, Lock> fallback = lockFallback(cfg);
    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM market_lock_fallback");
      statement.execute("DELETE FROM market_effective_price");
    }
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO market_lock_fallback(item,lock_buy,lock_sell) VALUES(?,?,?)")) {
      for (var entry : fallback.entrySet()) {
        insert.setString(1, entry.getKey().name());
        insert.setBoolean(2, entry.getValue().buy());
        insert.setBoolean(3, entry.getValue().sell());
        insert.addBatch();
      }
      insert.executeBatch();
    }
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO market_effective_price(item,buy_price,sell_price,base_price) VALUES(?,?,?,?)")) {
      for (var entry : quotes.entrySet()) {
        insert.setString(1, entry.getKey().name());
        if (entry.getValue().buy() == null) insert.setNull(2, Types.DECIMAL); else insert.setDouble(2, entry.getValue().buy());
        insert.setDouble(3, entry.getValue().sell());
        insert.setDouble(4, entry.getValue().base());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }
  private MarketEffective() {}
}
