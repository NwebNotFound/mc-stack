package de.mcstack.craftcore;

import java.sql.*;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;

/**
 * Optionale Deckel fuer den Bank-Ankauf einzelner Items ({@code market.buyback_caps}).
 * Abschaltbar ueber {@code market.buyback_caps.enabled}; aus = keinerlei Wirkung. Items werden nicht gesperrt,
 * nur der Ankaufspreis (max-price, CP) und die Stueckzahl pro Markttag und Spieler/Discord-Verknuepfung (max-per-day) begrenzt.
 */
final class BuybackCaps {
  private BuybackCaps() {}
  static boolean enabled(Configuration cfg) { return cfg.getBoolean("market.buyback_caps.enabled", false); }
  static boolean capped(Configuration cfg, Material m) { return enabled(cfg) && cfg.isConfigurationSection("market.buyback_caps.items." + m.name()); }
  /** Ankauf in Cent nach Deckel. */
  static long capCents(Configuration cfg, Material m, long cents) {
    if (!capped(cfg, m)) return cents;
    double max = cfg.getDouble("market.buyback_caps.items." + m.name() + ".max-price", -1);
    if (!Double.isFinite(max) || max < 0) return cents;
    return Math.min(cents, Math.round(max * 100));
  }
  static int perDay(Configuration cfg, Material m) {
    return capped(cfg, m) ? cfg.getInt("market.buyback_caps.items." + m.name() + ".max-per-day", Integer.MAX_VALUE) : Integer.MAX_VALUE;
  }
  static void ensureSchema(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS bank_sell_cap_daily (owner VARCHAR(40) NOT NULL, item VARCHAR(64) NOT NULL, day_key VARCHAR(10) NOT NULL, amount INT NOT NULL, PRIMARY KEY(owner,item,day_key))"); }
  }
  /** Reserviert ein Stueck im Tageskontingent; false = Kontingent erschoepft. Ohne Deckel immer true. */
  static boolean reserve(Connection c, Configuration cfg, String owner, Material m, String day) throws SQLException {
    int limit = perDay(cfg, m);
    if (limit == Integer.MAX_VALUE) return true;
    if (limit < 1) return false;
    try (PreparedStatement s = c.prepareStatement("INSERT IGNORE INTO bank_sell_cap_daily(owner,item,day_key,amount) VALUES(?,?,?,0)")) {
      s.setString(1, owner); s.setString(2, m.name()); s.setString(3, day); s.executeUpdate();
    }
    try (PreparedStatement s = c.prepareStatement("UPDATE bank_sell_cap_daily SET amount=amount+1 WHERE owner=? AND item=? AND day_key=? AND amount<?")) {
      s.setString(1, owner); s.setString(2, m.name()); s.setString(3, day); s.setInt(4, limit); return s.executeUpdate() == 1;
    }
  }
}
