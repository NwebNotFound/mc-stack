package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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
 * Bukkit side of the scarcity factor. Only started when {@code market.scarcity.enabled} is true.
 * Supply: last-seen inventories and ender chests (join, quit, periodic pass over online players) plus open listings.
 * Demand: net ingredient use from crafting, stonecutter and furnaces, collected in a {@link DemandLedger} on the server
 * thread and written in one batched transaction per flush.
 */
final class MarketSupply implements Listener {
  private static volatile Map<String, SupplyStore.Sample> samples = Map.of();
  private static volatile Scarcity.Settings active = null;
  private static volatile MarketSupply running = null;
  private final CraftCore core;
  private final DemandLedger ledger = new DemandLedger();
  private final DemandFlusher flusher;
  private final DemandSpill spill;

  private MarketSupply(CraftCore core) {
    this.core = core;
    this.spill = new DemandSpill(new java.io.File(core.getDataFolder(), "scarcity-demand-spill.yml"));
    this.flusher = new DemandFlusher(ledger, this::write, spill::save, msg -> core.getLogger().warning(msg));
  }

  static void start(CraftCore core) throws Exception {
    Scarcity.Settings settings = Scarcity.settings(core.getConfig());
    if (!settings.enabled()) { active = null; samples = Map.of(); return; }
    samples = Map.copyOf(core.tx(c -> { SupplyStore.migrate(c); return SupplyStore.load(c); }));
    active = settings;
    MarketSupply supply = new MarketSupply(core);
    running = supply;
    // Demand that could not reach the database at the last shutdown goes into the next flush.
    supply.ledger.add(supply.spill.takeAll());
    Bukkit.getPluginManager().registerEvents(supply, core);
    long period = Math.max(20L, core.getConfig().getLong("market.scarcity.period-ticks", 1200L));
    long flush = Math.max(20L, core.getConfig().getLong("market.scarcity.demand-flush-ticks", 100L));
    Bukkit.getScheduler().runTaskTimer(core, supply::scanOnline, 40L, period);
    Bukkit.getScheduler().runTaskTimerAsynchronously(core, supply.flusher::flush, flush, flush);
  }

  /** Orderly shutdown (Market.shutdown): see {@link DemandFlusher#stop}. Leftovers go to the spill file. */
  static void stop() {
    MarketSupply supply = running;
    running = null;
    if (supply != null) supply.flusher.stop(5000);
  }

  /** Price multiplier for one item. 1 when the feature is off or nothing is known about the item. */
  static double multiplier(String item, double base, double halfLifeHours, long now) {
    Scarcity.Settings settings = active;
    if (settings == null) return 1;
    SupplyStore.Sample sample = samples.get(item);
    if (sample == null) return 1;
    double demand = Scarcity.decay(sample.demand(), Math.max(0, now - sample.demandUpdated()), halfLifeHours);
    return Scarcity.factor(base, sample.smoothed(), demand, settings, settings.unique().contains(item));
  }

  /* ---------- supply ---------- */

  private void scanOnline() {
    List<Counted> players = new ArrayList<>();
    for (Player player : Bukkit.getOnlinePlayers()) players.add(snapshot(player));
    // Nobody online means no new information: keep the last EMA instead of drifting to "zero stock" (#109 review).
    if (players.isEmpty()) return;
    Bukkit.getScheduler().runTaskAsynchronously(core, () -> store(players, true));
  }

  private void capture(Player player) {
    Counted counted = snapshot(player);
    Bukkit.getScheduler().runTaskAsynchronously(core, () -> store(List.of(counted), false));
  }

  private static Counted snapshot(Player player) {
    Map<String, Long> counts = new HashMap<>();
    tally(counts, player.getInventory().getStorageContents());
    tally(counts, player.getEnderChest().getContents());
    return new Counted("player:" + player.getUniqueId(), counts, System.currentTimeMillis());
  }

  private static void tally(Map<String, Long> counts, ItemStack[] stacks) {
    if (stacks == null) return;
    for (ItemStack stack : stacks) {
      if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
      counts.merge(stack.getType().name(), (long) stack.getAmount(), Long::sum);
    }
  }

  private void store(List<Counted> holders, boolean recompute) {
    Scarcity.Settings settings = active;
    if (settings == null) return;
    try {
      long now = System.currentTimeMillis();
      Map<String, SupplyStore.Sample> loaded = core.tx(c -> {
        for (Counted holder : holders) SupplyStore.replaceHolder(c, holder.holder(), holder.counts(), holder.captured());
        if (recompute) {
          SupplyStore.replaceHolder(c, "market", SupplyStore.marketStock(c), now);
          SupplyStore.recompute(c, settings.emaAlpha(), now, settings.holderTtlMs(), settings.minHolders());
        }
        return SupplyStore.load(c);
      });
      publish(loaded);
    } catch (Exception e) { core.getLogger().warning("Markt-Bestand: " + e.getMessage()); }
  }

  /* ---------- demand ---------- */

  /** Writes one batch. {@link DemandFlusher} owns retries, restore and spill. */
  private void write(Map<String, Long> batch) throws Exception {
    if (batch.isEmpty()) return;
    {
      long now = System.currentTimeMillis();
      CurvePrice.Factors factors = CurvePrice.factors(core.getConfig());
      Map<String, Double> halves = new HashMap<>();
      for (String item : batch.keySet()) {
        Double base = Market.instance == null ? null : Market.instance.basePrice(item);
        halves.put(item, CurvePrice.halfLifeHours(base == null ? 10 : base, factors));
      }
      Map<String, SupplyStore.Sample> loaded = core.tx(c -> { SupplyStore.addDemand(c, batch, halves, now); return SupplyStore.load(c); });
      publish(loaded);
    }
  }

  /** Both writers reload the whole table after their commit; the newer demand timestamp wins per item. */
  private static void publish(Map<String, SupplyStore.Sample> loaded) {
    synchronized (MarketSupply.class) {
      Map<String, SupplyStore.Sample> merged = new HashMap<>(loaded);
      for (var entry : samples.entrySet()) {
        SupplyStore.Sample stored = merged.get(entry.getKey());
        SupplyStore.Sample memory = entry.getValue();
        if (stored == null) merged.put(entry.getKey(), memory);
        else if (memory.demandUpdated() > stored.demandUpdated()) merged.put(entry.getKey(), new SupplyStore.Sample(stored.smoothed(), memory.demand(), memory.demandUpdated()));
      }
      samples = Map.copyOf(merged);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void craft(CraftItemEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || event.getRecipe() == null || event.getInventory() == null) return;
    ItemStack result = event.getRecipe().getResult();
    if (result == null || result.getType().isAir()) return;
    ItemStack[] matrix = event.getInventory().getMatrix();
    int[] amounts = new int[matrix == null ? 0 : matrix.length];
    String[] types = new String[amounts.length];
    for (int i = 0; i < amounts.length; i++) {
      ItemStack slot = matrix[i];
      if (slot == null || slot.getType().isAir() || slot.getAmount() <= 0) continue;
      amounts[i] = slot.getAmount();
      types[i] = slot.getType().name();
    }
    int max = Math.max(1, result.getMaxStackSize()), per = Math.max(1, result.getAmount());
    // Only clicks that really craft: shift moves into the inventory, everything else must have room for the result.
    boolean shift = event.isShiftClick() && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
    int[] space = shift ? space(player.getInventory().getStorageContents(), result, max) : new int[] {0, 0};
    int room = shift ? 0 : takeRoom(event, player, result, max);
    int crafts = CraftDemand.crafts(shift, amounts, per, max, space[0], space[1], room);
    ledger.add(CraftDemand.craftDelta(types, crafts, result.getType().name(), per));
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
    int max = Math.max(1, result.getMaxStackSize()), per = result.getAmount();
    int[] space = shift ? space(player.getInventory().getStorageContents(), result, max) : new int[] {0, 0};
    int room = shift ? 0 : takeRoom(event, player, result, max);
    int takes = CraftDemand.stonecutterTakes(shift, input.getAmount(), per, max, space[0], space[1], room);
    ledger.add(CraftDemand.stonecutterDelta(input.getType().name(), takes, result.getType().name(), per));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void smelt(FurnaceSmeltEvent event) {
    Material block = event.getBlock().getType();
    if (block != Material.FURNACE && block != Material.BLAST_FURNACE && block != Material.SMOKER) return;
    ItemStack source = event.getSource(), result = event.getResult();
    if (source == null) return;
    ledger.add(CraftDemand.smeltDelta(source.getType().name(), result == null ? null : result.getType().name(), result == null ? 1 : result.getAmount()));
  }

  /** Empty slots and free room in stacks the result can merge with (same item and meta, not just the same material). */
  private static int[] space(ItemStack[] storage, ItemStack result, int maxStack) {
    int empty = 0, partial = 0;
    if (storage != null) for (ItemStack slot : storage) {
      if (slot == null || slot.getType().isAir()) empty++;
      else if (slot.isSimilar(result)) partial += Math.max(0, maxStack - slot.getAmount());
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
    if (!cursor.isSimilar(result)) return 0;
    return Math.max(0, maxStack - cursor.getAmount());
  }

  private static boolean emptyHotbar(InventoryClickEvent event, Player player) {
    ItemStack dest = event.getClick() == ClickType.SWAP_OFFHAND
      ? player.getInventory().getItemInOffHand()
      : event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
    return dest == null || dest.getType().isAir();
  }

  @EventHandler public void join(PlayerJoinEvent event) { capture(event.getPlayer()); }
  /** The inventory a player logs out with stays part of the supply (until holder-ttl-days). */
  @EventHandler public void quit(PlayerQuitEvent event) { capture(event.getPlayer()); }

  private record Counted(String holder, Map<String, Long> counts, long captured) {}
}
