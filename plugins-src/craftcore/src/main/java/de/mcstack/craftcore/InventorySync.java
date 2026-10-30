package de.mcstack.craftcore;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Inventory snapshots for the website. Lobby and Survival both write rows.
 * v_player_inventory prefers Survival so a lobby compass does not replace the survival items.
 * An empty survival snapshot still hides lobby rows.
 */
final class InventorySync implements Listener {
  static final long PERIOD_TICKS = 6000L;
  static final int SUMMARY_LIMIT = 80;
  static final String VIEW = "CREATE OR REPLACE VIEW v_player_inventory AS SELECT i.uuid, i.slot, i.material, i.amount, i.meta_summary, i.updated_at FROM player_inventory i WHERE i.server=CASE WHEN EXISTS (SELECT 1 FROM player_inventory_sync s WHERE s.uuid=i.uuid AND s.server='survival') THEN 'survival' ELSE 'lobby' END";
  record Slot(int slot, String material, int amount, String metaSummary) {}
  private final CraftCore core;
  private final String server;
  private final ExecutorService writer = Executors.newSingleThreadExecutor();
  InventorySync(CraftCore core, String server) { this.core = core; this.server = server.toLowerCase(Locale.ROOT); }
  static boolean tracks(String serverName) { return SpawnRoute.isLobby(serverName) || SpawnRoute.isSurvival(serverName); }
  static void migrate(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS player_inventory (uuid CHAR(36) NOT NULL, server VARCHAR(32) NOT NULL, slot INT NOT NULL, material VARCHAR(64) NOT NULL, amount INT NOT NULL, meta_summary VARCHAR(80) NOT NULL, updated_at TIMESTAMP NOT NULL, PRIMARY KEY(uuid, server, slot))");
      s.execute("CREATE TABLE IF NOT EXISTS player_inventory_sync (uuid CHAR(36) NOT NULL, server VARCHAR(32) NOT NULL, updated_at TIMESTAMP NOT NULL, PRIMARY KEY(uuid, server))");
      s.execute(VIEW);
    }
  }
  static String enchantSummary(String key, int level) {
    String raw = key == null ? "" : key;
    int colon = raw.lastIndexOf(':');
    if (colon >= 0) raw = raw.substring(colon + 1);
    raw = raw.replace('_', ' ').trim();
    if (!raw.isEmpty()) raw = Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    return (raw + " " + roman(level)).trim();
  }
  static List<String> enchantLines(Map<String, Integer> enchants) {
    return enchants.entrySet().stream().map(e -> enchantSummary(e.getKey(), e.getValue())).sorted().toList();
  }
  static String metaSummary(String displayName, List<String> enchantSummaries) {
    if (displayName != null && !displayName.trim().isEmpty()) return clip(displayName.trim());
    if (enchantSummaries == null || enchantSummaries.isEmpty()) return "";
    return clip(String.join(", ", enchantSummaries));
  }
  static String clip(String value) { return value.length() <= SUMMARY_LIMIT ? value : value.substring(0, SUMMARY_LIMIT); }
  static String roman(int level) {
    String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    return level >= 1 && level < numerals.length ? numerals[level] : Integer.toString(level);
  }
  static String summary(ItemStack item) {
    if (item == null || item.getType().isAir()) return "";
    ItemMeta meta = item.getItemMeta();
    String name = null;
    if (meta != null && meta.customName() != null) name = PlainTextComponentSerializer.plainText().serialize(meta.customName());
    Map<String, Integer> enchants = new LinkedHashMap<>();
    if (meta != null) for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) enchants.put(entry.getKey().getKey().getKey(), entry.getValue());
    return metaSummary(name, enchantLines(enchants));
  }
  static List<Slot> fromInventory(PlayerInventory inventory) {
    List<Slot> slots = new ArrayList<>();
    for (int i = 0; i < inventory.getSize(); i++) {
      ItemStack item = inventory.getItem(i);
      if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
      slots.add(new Slot(i, item.getType().name(), item.getAmount(), summary(item)));
    }
    return List.copyOf(slots);
  }
  static void replace(Connection c, String uuid, String server, List<Slot> slots, Timestamp updated) throws SQLException {
    try (PreparedStatement d = c.prepareStatement("DELETE FROM player_inventory WHERE uuid=? AND server=?")) {
      d.setString(1, uuid); d.setString(2, server); d.executeUpdate();
    }
    try (PreparedStatement h = c.prepareStatement("INSERT INTO player_inventory_sync(uuid,server,updated_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE updated_at=VALUES(updated_at)")) {
      h.setString(1, uuid); h.setString(2, server); h.setTimestamp(3, updated); h.executeUpdate();
    }
    try (PreparedStatement q = c.prepareStatement("INSERT INTO player_inventory(uuid,server,slot,material,amount,meta_summary,updated_at) VALUES(?,?,?,?,?,?,?)")) {
      for (Slot slot : slots) {
        if (slot.slot() < 0 || slot.material() == null || slot.material().isBlank() || slot.amount() <= 0) continue;
        q.setString(1, uuid); q.setString(2, server); q.setInt(3, slot.slot()); q.setString(4, slot.material());
        q.setInt(5, slot.amount()); q.setString(6, slot.metaSummary() == null ? "" : clip(slot.metaSummary())); q.setTimestamp(7, updated); q.addBatch();
      }
      q.executeBatch();
    }
  }
  void start() throws Exception {
    core.tx(c -> { migrate(c); return null; });
    Bukkit.getPluginManager().registerEvents(this, core);
    Bukkit.getScheduler().runTaskTimer(core, () -> Bukkit.getOnlinePlayers().forEach(this::capture), PERIOD_TICKS, PERIOD_TICKS);
  }
  private void capture(Player player) {
    UUID uuid = player.getUniqueId();
    List<Slot> slots = fromInventory(player.getInventory());
    Timestamp updated = Timestamp.from(Instant.now());
    writer.execute(() -> { try { core.tx(c -> { replace(c, uuid.toString(), server, slots, updated); return null; }); } catch (Exception e) { core.getLogger().warning("Inventar speichern fehlgeschlagen: " + e.getClass().getSimpleName()); } });
  }
  @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { capture(event.getPlayer()); }
  void shutdown() {
    Bukkit.getOnlinePlayers().forEach(this::capture);
    writer.shutdown();
    try { if (!writer.awaitTermination(30, TimeUnit.SECONDS)) core.getLogger().warning("Inventar-Speicherung noch nicht abgeschlossen"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
