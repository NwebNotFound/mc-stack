package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Database side of the scarcity factor. Pure JDBC so it can be tested against H2. */
final class SupplyStore {
  record Sample(double smoothed, double demand, long demandUpdated) {}

  private SupplyStore() {}

  static void migrate(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS market_supply_holder (holder VARCHAR(80) NOT NULL, item VARCHAR(64) NOT NULL, qty BIGINT NOT NULL, seen BIGINT NOT NULL, PRIMARY KEY(holder, item))");
      s.execute("CREATE TABLE IF NOT EXISTS market_supply (item VARCHAR(64) PRIMARY KEY, raw_qty BIGINT NOT NULL, smoothed DOUBLE NOT NULL, updated BIGINT NOT NULL, demand DOUBLE NOT NULL DEFAULT 0, demand_updated BIGINT NOT NULL DEFAULT 0)");
    }
  }

  /**
   * Adds a batch of net demand in one statement batch. Decay and addition happen inside the row update
   * ({@code demand = demand * 2^(-dt/H) + delta}), so two writers on the same item can never overwrite each other.
   */
  static void addDemand(Connection c, Map<String, Long> delta, Map<String, Double> halfLifeHours, long now) throws SQLException {
    if (delta == null || delta.isEmpty()) return;
    String sql = "INSERT INTO market_supply(item, raw_qty, smoothed, updated, demand, demand_updated) VALUES(?,0,-1,?,?,?) "
      + "ON DUPLICATE KEY UPDATE demand = demand * POWER(0.5, GREATEST(0, VALUES(demand_updated) - demand_updated) / ?) + VALUES(demand), "
      + "demand_updated = GREATEST(demand_updated, VALUES(demand_updated))";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (var entry : new java.util.TreeMap<>(delta).entrySet()) {
        if (entry.getValue() == null || entry.getValue() == 0) continue;
        double half = halfLifeHours == null ? 72d : halfLifeHours.getOrDefault(entry.getKey(), 72d);
        ps.setString(1, entry.getKey());
        ps.setLong(2, now);
        ps.setDouble(3, entry.getValue());
        ps.setLong(4, now);
        ps.setDouble(5, Math.max(1e-6, half) * 3_600_000d);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /** Last-seen inventory of one holder. Holders stay after a quit, so an empty server keeps its supply. */
  static void replaceHolder(Connection c, String holder, Map<String, Long> counts, long now) throws SQLException {
    try (PreparedStatement delete = c.prepareStatement("DELETE FROM market_supply_holder WHERE holder=?")) {
      delete.setString(1, holder);
      delete.executeUpdate();
    }
    try (PreparedStatement insert = c.prepareStatement("INSERT INTO market_supply_holder(holder, item, qty, seen) VALUES(?,?,?,?)")) {
      boolean any = false;
      // An empty inventory still records that the holder was seen, so it counts towards min-holders.
      Map<String, Long> rows = counts == null || counts.isEmpty() ? Map.of("AIR", 0L) : counts;
      for (var entry : rows.entrySet()) {
        if (entry.getValue() == null || entry.getValue() < 0) continue;
        insert.setString(1, holder);
        insert.setString(2, entry.getKey());
        insert.setLong(3, entry.getValue());
        insert.setLong(4, now);
        insert.addBatch();
        any = true;
      }
      if (any) insert.executeBatch();
    }
  }

  static Map<String, Long> marketStock(Connection c) {
    Map<String, Long> totals = new HashMap<>();
    addStock(c, totals, "SELECT material, SUM(amount) FROM auctions WHERE status='OPEN' GROUP BY material");
    addStock(c, totals, "SELECT material, SUM(amount) FROM ah_journal WHERE status='OPEN' GROUP BY material");
    return totals;
  }

  private static void addStock(Connection c, Map<String, Long> totals, String sql) {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
      while (r.next()) if (r.getString(1) != null) totals.merge(r.getString(1), r.getLong(2), Long::sum);
    } catch (SQLException ignored) {}
  }

  /**
   * Slides the supply EMA towards the current holder totals. Holders older than {@code ttlMs} are dropped first.
   * With fewer than {@code minHolders} player holders the sample says nothing about the economy, so the EMA stays.
   * Returns the number of player holders that were counted (0 = no update).
   */
  static int recompute(Connection c, double alpha, long now, long ttlMs, int minHolders) throws SQLException {
    try (PreparedStatement purge = c.prepareStatement("DELETE FROM market_supply_holder WHERE seen < ?")) {
      purge.setLong(1, now - ttlMs);
      purge.executeUpdate();
    }
    int players;
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(DISTINCT holder) FROM market_supply_holder WHERE holder LIKE 'player:%'")) {
      players = r.next() ? r.getInt(1) : 0;
    }
    if (players < Math.max(1, minHolders)) return 0;
    Map<String, Long> raw = new HashMap<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT item, SUM(qty) FROM market_supply_holder WHERE item <> 'AIR' GROUP BY item")) {
      while (r.next()) raw.put(r.getString(1), r.getLong(2));
    }
    Map<String, Double> previous = new HashMap<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT item, smoothed FROM market_supply")) {
      while (r.next()) previous.put(r.getString(1), r.getDouble(2));
    }
    Set<String> items = new HashSet<>(raw.keySet());
    for (var entry : previous.entrySet()) if (entry.getValue() >= 0) items.add(entry.getKey());
    String sql = "INSERT INTO market_supply(item, raw_qty, smoothed, updated) VALUES(?,?,?,?) "
      + "ON DUPLICATE KEY UPDATE raw_qty=VALUES(raw_qty), smoothed=VALUES(smoothed), updated=VALUES(updated)";
    try (PreparedStatement update = c.prepareStatement(sql)) {
      boolean pending = false;
      for (String item : new java.util.TreeSet<>(items)) {
        long qty = raw.getOrDefault(item, 0L);
        double smooth = Scarcity.ema(previous.getOrDefault(item, -1d), qty, alpha);
        update.setString(1, item);
        update.setLong(2, qty);
        update.setDouble(3, smooth);
        update.setLong(4, now);
        update.addBatch();
        pending = true;
      }
      if (pending) update.executeBatch();
    }
    return players;
  }

  static Map<String, Sample> load(Connection c) throws SQLException {
    Map<String, Sample> rows = new HashMap<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT item, smoothed, demand, demand_updated FROM market_supply")) {
      while (r.next()) rows.put(r.getString(1), new Sample(r.getDouble(2), r.getDouble(3), r.getLong(4)));
    }
    return rows;
  }
}
