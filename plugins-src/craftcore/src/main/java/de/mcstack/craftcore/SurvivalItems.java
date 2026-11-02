package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;

/** Survival-obtainable items. The built-in rule always applies; config only adds names. */
final class SurvivalItems {
  /** Creative or technical items that are not items a survival player can obtain. */
  private static final Set<String> BLOCKED = Set.of(
    "BARRIER", "BEDROCK", "LIGHT",
    "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
    "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
    "DEBUG_STICK", "KNOWLEDGE_BOOK",
    "SPAWNER", "TRIAL_SPAWNER", "VAULT",
    "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST", "END_PORTAL_FRAME", "PETRIFIED_OAK_SLAB",
    "FARMLAND", "DIRT_PATH", "CHORUS_PLANT", "FROGSPAWN", "PLAYER_HEAD",
    "END_PORTAL", "END_GATEWAY", "NETHER_PORTAL",
    "FIRE", "SOUL_FIRE", "WATER", "LAVA", "BUBBLE_COLUMN",
    "MOVING_PISTON", "PISTON_HEAD", "FROSTED_ICE",
    "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "POWDER_SNOW",
    "CAVE_VINES_PLANT", "TWISTING_VINES_PLANT", "WEEPING_VINES_PLANT",
    "KELP_PLANT", "SWEET_BERRY_BUSH", "TORCHFLOWER_CROP", "PITCHER_CROP", "BAMBOO_SAPLING");

  private static final String[][] CATALOG = {
    {"market_catalog", "item"},
    {"market_prices", "material"},
    {"market_effective_price", "item"},
    {"market_lock_fallback", "item"},
    {"market_locks", "item"},
    {"bank_price_factors", "material"}
  };

  private SurvivalItems() {}

  static boolean obtainable(Material material) { return obtainable(material, List.of()); }

  static boolean obtainable(Configuration cfg, Material material) {
    return material != null && obtainable(material.name(), cfg == null ? List.of() : cfg.getStringList("market.unobtainable"));
  }

  /** Name rules only. Does not touch the Bukkit registry, so unit tests can call it. */
  static boolean obtainable(String name, Collection<String> extra) {
    if (name == null || name.isBlank()) return false;
    String normalized = name.toUpperCase(Locale.ROOT);
    if (normalized.startsWith("MINECRAFT:")) normalized = normalized.substring(10);
    if (normalized.equals("AIR") || normalized.equals("CAVE_AIR") || normalized.equals("VOID_AIR") || normalized.startsWith("LEGACY_")) return false;
    if (normalized.endsWith("_SPAWN_EGG") || normalized.startsWith("INFESTED_") || normalized.contains("COMMAND_BLOCK")) return false;
    if (BLOCKED.contains(normalized)) return false;
    if (extra != null) for (String raw : extra) if (raw != null && raw.toUpperCase(Locale.ROOT).equals(normalized)) return false;
    return true;
  }

  static boolean obtainable(Material material, Collection<String> extra) {
    return material != null && obtainable(material.name(), extra);
  }

  /** Drops catalog and live price rows for items that are not survival-obtainable. Missing tables are ignored. */
  static int purge(Connection c, Predicate<String> blocked) throws SQLException {
    int removed = 0;
    for (String[] table : CATALOG) {
      List<String> drop = new ArrayList<>();
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT " + table[1] + " FROM " + table[0])) {
        while (r.next()) {
          String name = r.getString(1);
          if (name != null && blocked.test(name)) drop.add(name);
        }
      } catch (SQLException ex) { if (!missingTable(ex)) throw ex; continue; }
      if (drop.isEmpty()) continue;
      try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + table[0] + " WHERE " + table[1] + "=?")) {
        for (String name : drop) { delete.setString(1, name); delete.addBatch(); }
        delete.executeBatch();
      }
      removed += drop.size();
    }
    return removed;
  }

  /** MySQL/MariaDB 1146 and ANSI 42S02. Other SQL errors must surface. */
  static boolean missingTable(SQLException ex) {
    if (ex == null) return false;
    if ("42S02".equals(ex.getSQLState()) || ex.getErrorCode() == 1146) return true;
    String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
    return message.contains("table") && (message.contains("not found") || message.contains("doesn't exist") || message.contains("does not exist"));
  }
}
