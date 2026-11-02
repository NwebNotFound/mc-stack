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
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.StonecutterInventory;

/**
 * Supply is the sum of online inventories, ender chests, and open market listings.
 * Inventory copies happen on the main thread; the database update is asynchronous.
 * Loaded-chunk containers are not scanned.
 */
final class MarketSupply implements Listener {
  record Sample(double smoothed, double demand, long demandUpdated) {}
  private static volatile Map<String, Sample> samples = Map.of();
  private final CraftCore core;

  private MarketSupply(CraftCore core) { this.core = core; }

  static void start(CraftCore core) throws Exception {
    samples = Map.copyOf(core.tx(c -> { migrate(c); return loadSamples(c); }));
    Bukkit.getPluginManager().registerEvents(new MarketSupply(core), core);
    long period = Math.max(20L, core.getConfig().getLong("market.scarcity.period-ticks", 1200L));
    Bukkit.getScheduler().runTaskTimer(core, () -> scanOnline(core), 40L, period);
  }

  static double multiplier(String item, double base, Scarcity.Settings settings) {
    return multiplier(item, base, settings, base < CurvePrice.CHEAP_BASE ? 12 : 72, System.currentTimeMillis());
  }

  static double multiplier(String item, double base, Scarcity.Settings settings, double halfLifeHours, long now) {
    if (!settings.enabled()) return 1;
    Sample sample = samples.get(item);
    if (sample == null) return 1;
    double decayed = Scarcity.decay(sample.demand(), Math.max(0, now - sample.demandUpdated()), halfLifeHours);
    return Scarcity.factor(base, sample.smoothed(), decayed, settings, settings.unique().contains(item));
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
      s.execute("CREATE TABLE IF NOT EXISTS market_supply (item VARCHAR(64) PRIMARY KEY, raw_qty BIGINT NOT NULL, smoothed DOUBLE NOT NULL, updated BIGINT NOT NULL, demand DOUBLE NOT NULL DEFAULT 0, demand_updated BIGINT NOT NULL DEFAULT 0)");
      try { s.execute("ALTER TABLE market_supply ADD COLUMN IF NOT EXISTS demand DOUBLE NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
      try { s.execute("ALTER TABLE market_supply ADD COLUMN IF NOT EXISTS demand_updated BIGINT NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
    }
  }

  /** Decay stored demand, add crafts, and return the updated samples. */
  static Map<String, Sample> addDemand(Connection c, Map<String, Long> used, long now, Map<String, Double> halfLifeHours) throws SQLException {
    Map<String, Sample> changed = new HashMap<>();
    if (used == null || used.isEmpty()) return changed;
    try (PreparedStatement read = c.prepareStatement("SELECT smoothed, demand, demand_updated FROM market_supply WHERE item=?")) {
      try (PreparedStatement write = c.prepareStatement("INSERT INTO market_supply(item, raw_qty, smoothed, updated, demand, demand_updated) VALUES(?,0,?,?,?,?) ON DUPLICATE KEY UPDATE demand=VALUES(demand), demand_updated=VALUES(demand_updated)")) {
        for (var entry : used.entrySet()) {
          if (entry.getValue() == null || entry.getValue() <= 0) continue;
          double smoothedSupply = -1;
          double demand = 0;
          long updated = 0;
          read.setString(1, entry.getKey());
          try (ResultSet row = read.executeQuery()) {
            if (row.next()) { smoothedSupply = row.getDouble(1); demand = row.getDouble(2); updated = row.getLong(3); }
          }
          double half = halfLifeHours.getOrDefault(entry.getKey(), 72d);
          double decayed = Scarcity.decay(demand, updated <= 0 ? 0 : Math.max(0, now - updated), half);
          double next = decayed + entry.getValue();
          write.setString(1, entry.getKey());
          write.setDouble(2, smoothedSupply);
          write.setLong(3, now);
          write.setDouble(4, next);
          write.setLong(5, now);
          write.addBatch();
          changed.put(entry.getKey(), new Sample(smoothedSupply, next, now));
        }
        write.executeBatch();
      }
    }
    return changed;
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
      boolean pending = false;
      for (String item : items) {
        long qty = raw.getOrDefault(item, 0L);
        double prev = previous.getOrDefault(item, -1d);
        // A craft-only row stores smoothed < 0. Zero stock would look scarce and lift the price.
        if (prev < 0 && !raw.containsKey(item)) continue;
        double smooth = Scarcity.ema(prev, qty, alpha);
        next.put(item, smooth);
        update.setString(1, item);
        update.setLong(2, qty);
        update.setDouble(3, smooth);
        update.setLong(4, now);
        update.addBatch();
        pending = true;
      }
      if (pending) update.executeBatch();
    }
    return next;
  }

  static Map<String, Sample> loadSamples(Connection c) throws SQLException {
    Map<String, Sample> rows = new HashMap<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT item, smoothed, demand, demand_updated FROM market_supply")) {
      while (r.next()) rows.put(r.getString(1), new Sample(r.getDouble(2), r.getDouble(3), r.getLong(4)));
    }
    return rows;
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
      Map<String, Sample> loaded = core.tx(c -> {
        Set<String> online = new HashSet<>();
        for (Counted holder : holders) {
          if (holder.holder().startsWith("player:")) online.add(holder.holder());
          replaceHolder(c, holder.holder(), holder.counts());
        }
        if (allOnline) retainOnline(c, online);
        if (refreshMarket) replaceHolder(c, "market", marketStock(c));
        recompute(c, settings.emaAlpha(), System.currentTimeMillis());
        return loadSamples(c);
      });
      publish(loaded);
    } catch (Exception e) { core.getLogger().warning("Markt-Bestand: " + e.getMessage()); }
  }

  private void recordDemand(Map<String, Long> used) {
    try {
      long now = System.currentTimeMillis();
      CurvePrice.Factors factors = CurvePrice.factors(core.getConfig());
      Map<String, Double> halves = new HashMap<>();
      for (String item : used.keySet()) {
        Double base = Market.instance == null ? null : Market.instance.basePrice(item);
        halves.put(item, CurvePrice.halfLifeHours(base == null ? 10 : base, factors));
      }
      Map<String, Sample> changed = core.tx(c -> addDemand(c, used, now, halves));
      synchronized (MarketSupply.class) {
        Map<String, Sample> merged = new HashMap<>(samples);
        merged.putAll(changed);
        samples = Map.copyOf(merged);
      }
    } catch (Exception e) { core.getLogger().warning("Craft-Nachfrage: " + e.getMessage()); }
  }

  @EventHandler(ignoreCancelled = true)
  public void craft(CraftItemEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || event.getRecipe() == null || event.getInventory() == null) return;
    ItemStack result = event.getRecipe().getResult();
    if (result == null) return;
    ItemStack[] matrix = event.getInventory().getMatrix();
    int[] amounts = new int[matrix == null ? 0 : matrix.length];
    String[] types = new String[amounts.length];
    for (int i = 0; i < amounts.length; i++) {
      ItemStack slot = matrix[i];
      if (slot == null || slot.getType().isAir() || slot.getAmount() <= 0) continue;
      amounts[i] = slot.getAmount();
      types[i] = slot.getType().name();
    }
    int empty = 0, partial = 0, max = Math.max(1, result.getMaxStackSize()), per = Math.max(1, result.getAmount());
    ItemStack[] storage = player.getInventory().getStorageContents();
    if (storage != null) for (ItemStack slot : storage) {
      if (slot == null || slot.getType().isAir()) empty++;
      else if (slot.getType() == result.getType()) partial += Math.max(0, max - slot.getAmount());
    }
    int crafts = CraftDemand.crafts(event.isShiftClick(), amounts, per, max, empty, partial);
    Map<String, Long> used = CraftDemand.consumed(types, crafts);
    if (!used.isEmpty()) Bukkit.getScheduler().runTaskAsynchronously(core, () -> recordDemand(used));
  }

  /** Result slot of a stonecutter. Vanilla StonecutterMenu.RESULT_SLOT. */
  private static final int STONECUTTER_RESULT = 1;

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void stonecut(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (!(event.getView().getTopInventory() instanceof StonecutterInventory stonecutter)) return;
    if (event.getRawSlot() != STONECUTTER_RESULT) return;
    ItemStack result = stonecutter.getResult();
    ItemStack input = stonecutter.getInputItem();
    if (result == null || input == null || result.getType().isAir() || input.getType().isAir()) return;
    if (result.getAmount() <= 0 || input.getAmount() <= 0) return;
    boolean shift = event.isShiftClick() && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
    int empty = 0, partial = 0, max = Math.max(1, result.getMaxStackSize()), per = result.getAmount();
    if (shift) {
      int[] space = space(player.getInventory().getStorageContents(), result.getType(), max);
      empty = space[0];
      partial = space[1];
    }
    int room = shift ? 0 : takeRoom(event, player, result, max);
    int takes = CraftDemand.stonecutterTakes(shift, input.getAmount(), per, max, empty, partial, room);
    Map<String, Long> used = CraftDemand.stonecutterConsumed(input.getType().name(), takes);
    if (!used.isEmpty()) Bukkit.getScheduler().runTaskAsynchronously(core, () -> recordDemand(used));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void smelt(FurnaceSmeltEvent event) {
    Material block = event.getBlock().getType();
    if (block != Material.FURNACE && block != Material.BLAST_FURNACE && block != Material.SMOKER) return;
    ItemStack source = event.getSource();
    if (source == null) return;
    Map<String, Long> used = CraftDemand.smelted(source.getType().name());
    if (!used.isEmpty()) Bukkit.getScheduler().runTaskAsynchronously(core, () -> recordDemand(used));
  }

  private static int[] space(ItemStack[] storage, Material type, int maxStack) {
    int empty = 0, partial = 0;
    if (storage != null) for (ItemStack slot : storage) {
      if (slot == null || slot.getType().isAir()) empty++;
      else if (slot.getType() == type) partial += Math.max(0, maxStack - slot.getAmount());
    }
    return new int[] {empty, partial};
  }

  /** How many result items this click can accept. A non-empty hotbar cannot swap into the result slot. */
  private static int takeRoom(InventoryClickEvent event, Player player, ItemStack result, int maxStack) {
    return switch (event.getAction()) {
      case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE -> cursorRoom(event.getCursor(), result, maxStack);
      case DROP_ALL_SLOT, DROP_ONE_SLOT -> maxStack;
      case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> emptyHotbar(event, player) ? maxStack : 0;
      default -> 0;
    };
  }

  private static int cursorRoom(ItemStack cursor, ItemStack result, int maxStack) {
    if (cursor == null || cursor.getType().isAir()) return maxStack;
    if (cursor.getType() != result.getType()) return 0;
    return Math.max(0, maxStack - cursor.getAmount());
  }

  private static boolean emptyHotbar(InventoryClickEvent event, Player player) {
    ItemStack dest = event.getClick() == ClickType.SWAP_OFFHAND
      ? player.getInventory().getItemInOffHand()
      : event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
    return dest == null || dest.getType().isAir();
  }

  @EventHandler public void join(PlayerJoinEvent event) { capture(event.getPlayer(), true); }
  @EventHandler public void quit(PlayerQuitEvent event) { capture(event.getPlayer(), false); }

  /** Keeps a craft that committed after this scan's read started. */
  private static void publish(Map<String, Sample> loaded) {
    synchronized (MarketSupply.class) {
      Map<String, Sample> merged = new HashMap<>(loaded);
      for (var entry : samples.entrySet()) {
        Sample memory = entry.getValue();
        Sample stored = merged.get(entry.getKey());
        if (stored == null || memory.demandUpdated() > stored.demandUpdated()) {
          double smoothed = stored == null ? memory.smoothed() : stored.smoothed();
          merged.put(entry.getKey(), new Sample(smoothed, memory.demand(), memory.demandUpdated()));
        }
      }
      samples = Map.copyOf(merged);
    }
  }

  private record Counted(String holder, Map<String, Long> counts) {}
}
