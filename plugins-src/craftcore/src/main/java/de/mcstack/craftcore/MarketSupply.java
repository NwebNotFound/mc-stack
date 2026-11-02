package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Supply is the sum of online inventories, ender chests, and open market listings.
 * Inventory copies happen on the main thread; the database update is asynchronous.
 * Loaded-chunk containers are not scanned.
 */
final class MarketSupply implements Listener {
  private static volatile Map<String, Double> smoothed = Map.of();
  private final CraftCore core;

  private MarketSupply(CraftCore core) { this.core = core; }

  static void start(CraftCore core) throws Exception {
    core.tx(c -> { migrate(c); return null; });
    Bukkit.getPluginManager().registerEvents(new MarketSupply(core), core);
    long period = Math.max(20L, core.getConfig().getLong("market.scarcity.period-ticks", 1200L));
    Bukkit.getScheduler().runTaskTimer(core, () -> scanOnline(core), 40L, period);
  }

  static double multiplier(String item, double base, Scarcity.Settings settings) {
    if (!settings.enabled()) return 1;
    Double supply = smoothed.get(item);
    if (supply == null) return 1;
    return Scarcity.factor(base, supply, settings, settings.unique().contains(item));
  }

  record CountedStack(String type, int amount) {}

  static Map<String, Long> tally(Iterable<ItemStack> stacks) {
    List<CountedStack> counted = new ArrayList<>();
    if (stacks != null) for (ItemStack stack : stacks) if (stack != null) counted.add(new CountedStack(stack.getType().name(), stack.getAmount()));
    return tallyStacks(counted);
  }

  static Map<String, Long> tallyStacks(Iterable<CountedStack> stacks) {
    Map<String, Long> counts = new HashMap<>();
    if (stacks == null) return counts;
    for (CountedStack stack : stacks) {
      if (stack == null || stack.type() == null || stack.type().equals("AIR") || stack.amount() <= 0) continue;
      counts.merge(stack.type(), (long) stack.amount(), Long::sum);
    }
    return counts;
  }

  static void migrate(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS market_supply_holder (holder VARCHAR(80) NOT NULL, item VARCHAR(64) NOT NULL, qty BIGINT NOT NULL, PRIMARY KEY(holder, item))");
      s.execute("CREATE TABLE IF NOT EXISTS market_supply (item VARCHAR(64) PRIMARY KEY, raw_qty BIGINT NOT NULL, smoothed DOUBLE NOT NULL, updated BIGINT NOT NULL)");
    }
  }

  static void replaceHolder(Connection c, String holder, Map<String, Long> counts) throws SQLException {
    try (PreparedStatement delete = c.prepareStatement("DELETE FROM market_supply_holder WHERE holder=?")) {
      delete.setString(1, holder);
      delete.executeUpdate();
    }
    if (counts == null || counts.isEmpty()) return;
    try (PreparedStatement insert = c.prepareStatement("INSERT INTO market_supply_holder(holder, item, qty) VALUES(?,?,?)")) {
      for (var entry : counts.entrySet()) {
        if (entry.getValue() == null || entry.getValue() <= 0) continue;
        insert.setString(1, holder);
        insert.setString(2, entry.getKey());
        insert.setLong(3, entry.getValue());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  /** Drops player holders that are not in {@code online}. */
  static void retainOnline(Connection c, Set<String> online) throws SQLException {
    List<String> stale = new ArrayList<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT DISTINCT holder FROM market_supply_holder WHERE holder LIKE 'player:%'")) {
      while (r.next()) if (!online.contains(r.getString(1))) stale.add(r.getString(1));
    }
    try (PreparedStatement delete = c.prepareStatement("DELETE FROM market_supply_holder WHERE holder=?")) {
      for (String holder : stale) { delete.setString(1, holder); delete.addBatch(); }
      delete.executeBatch();
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
      while (r.next()) totals.merge(r.getString(1), r.getLong(2), Long::sum);
    } catch (SQLException ignored) {}
  }

  static Map<String, Double> recompute(Connection c, double alpha, long now) throws SQLException {
    Map<String, Long> raw = new HashMap<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT item, SUM(qty) FROM market_supply_holder GROUP BY item")) {
      while (r.next()) raw.put(r.getString(1), r.getLong(2));
    }
    Map<String, Double> previous = new HashMap<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT item, smoothed FROM market_supply")) {
      while (r.next()) previous.put(r.getString(1), r.getDouble(2));
    }
    Set<String> items = new HashSet<>();
    items.addAll(raw.keySet());
    items.addAll(previous.keySet());
    Map<String, Double> next = new HashMap<>();
    try (PreparedStatement update = c.prepareStatement("INSERT INTO market_supply(item, raw_qty, smoothed, updated) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE raw_qty=VALUES(raw_qty), smoothed=VALUES(smoothed), updated=VALUES(updated)")) {
      for (String item : items) {
        long qty = raw.getOrDefault(item, 0L);
        double smooth = Scarcity.ema(previous.getOrDefault(item, -1d), qty, alpha);
        next.put(item, smooth);
        update.setString(1, item);
        update.setLong(2, qty);
        update.setDouble(3, smooth);
        update.setLong(4, now);
        update.addBatch();
      }
      if (!items.isEmpty()) update.executeBatch();
    }
    return next;
  }

  private static void scanOnline(CraftCore core) {
    List<Counted> players = new ArrayList<>();
    for (Player player : Bukkit.getOnlinePlayers()) players.add(snapshot(player));
    Bukkit.getScheduler().runTaskAsynchronously(core, () -> store(core, players, true, true));
  }

  private void capture(Player player, boolean present) {
    Counted counted = present ? snapshot(player) : new Counted("player:" + player.getUniqueId(), Map.of());
    Bukkit.getScheduler().runTaskAsynchronously(core, () -> store(core, List.of(counted), false, true));
  }

  private static Counted snapshot(Player player) {
    List<ItemStack> stacks = new ArrayList<>();
    ItemStack[] stored = player.getInventory().getStorageContents();
    if (stored != null) for (ItemStack stack : stored) if (stack != null) stacks.add(stack);
    ItemStack[] ender = player.getEnderChest().getContents();
    if (ender != null) for (ItemStack stack : ender) if (stack != null) stacks.add(stack);
    return new Counted("player:" + player.getUniqueId(), tally(stacks));
  }

  private static void store(CraftCore core, List<Counted> holders, boolean allOnline, boolean refreshMarket) {
    try {
      Scarcity.Settings settings = Scarcity.settings(core.getConfig());
      Map<String, Double> next = core.tx(c -> {
        Set<String> online = new HashSet<>();
        for (Counted holder : holders) {
          if (holder.holder().startsWith("player:")) online.add(holder.holder());
          replaceHolder(c, holder.holder(), holder.counts());
        }
        if (allOnline) retainOnline(c, online);
        if (refreshMarket) replaceHolder(c, "market", marketStock(c));
        Map<String, Double> smoothedSupply = recompute(c, settings.emaAlpha(), System.currentTimeMillis());
        return smoothedSupply;
      });
      smoothed = Map.copyOf(next);
    } catch (Exception e) { core.getLogger().warning("Markt-Bestand: " + e.getMessage()); }
  }

  @EventHandler public void join(PlayerJoinEvent event) { capture(event.getPlayer(), true); }
  @EventHandler public void quit(PlayerQuitEvent event) { capture(event.getPlayer(), false); }

  private record Counted(String holder, Map<String, Long> counts) {}
}
