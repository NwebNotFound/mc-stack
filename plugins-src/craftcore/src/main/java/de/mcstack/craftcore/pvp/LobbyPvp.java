package de.mcstack.craftcore.pvp;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class LobbyPvp implements Listener, TabExecutor {
    private record Kit(String name, String icon) {}
    private static final class Menu implements InventoryHolder {
        final List<Kit> kits;
        final int page;
        Inventory inventory;
        Menu(List<Kit> kits, int page) { this.kits = kits; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final PvpStore.ConnectionSource source;
    private final PvpStore store;
    private final PvpHostClient host = new PvpHostClient();
    private final PvpQueue queue = new PvpQueue();
    // Accessed only on the server thread, including while a pair is starting.
    private final Set<UUID> matching = new HashSet<>();

    public LobbyPvp(JavaPlugin plugin, PvpStore.ConnectionSource source) {
        this.plugin = plugin; this.source = source; this.store = new PvpStore(source);
    }
    public void start() {
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("pvp"));
        command.setExecutor(this); command.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Nur fuer Spieler."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("leave")) {
            player.sendMessage(queue.leave(player.getUniqueId()) ? "PvP-Queue verlassen" : "Du bist nicht in der PvP-Queue");
        } else if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("queue"))) {
            loadKits(player.getUniqueId());
        } else player.sendMessage("/pvp queue|leave");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("queue", "leave").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
    }
    private void loadKits(UUID id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Kit> kits = new ArrayList<>();
            try (Connection c = source.open(); PreparedStatement s = c.prepareStatement("SELECT name,icon FROM pvp_kits ORDER BY name"); ResultSet r = s.executeQuery()) {
                while (r.next()) kits.add(new Kit(r.getString(1), r.getString(2)));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(id);
                    if (player == null) return;
                    if (kits.isEmpty()) player.sendMessage("Noch keine Kits"); else open(player, kits, 0);
                });
            } catch (Exception e) {
                plugin.getLogger().warning("PvP-Kits konnten nicht geladen werden: " + e.getClass().getSimpleName());
                Bukkit.getScheduler().runTask(plugin, () -> message(id, "PvP-Kits konnten nicht geladen werden"));
            }
        });
    }
    private void open(Player player, List<Kit> kits, int page) {
        Menu menu = new Menu(kits, page);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("PvP Kits"));
        for (int i = 0; i < 45 && page * 45 + i < kits.size(); i++) {
            Kit kit = kits.get(page * 45 + i);
            Material material = Material.matchMaterial(kit.icon());
            if (material == null || !material.isItem() || material.isAir()) material = Material.IRON_SWORD;
            ItemStack item = new ItemStack(material);
            var meta = item.getItemMeta(); meta.displayName(Component.text(kit.name())); item.setItemMeta(meta);
            menu.inventory.setItem(i, item);
        }
        if (page > 0) menu.inventory.setItem(45, button("Zurueck"));
        if ((page + 1) * 45 < kits.size()) menu.inventory.setItem(53, button("Weiter"));
        player.openInventory(menu.inventory);
    }
    private ItemStack button(String name) {
        ItemStack item = new ItemStack(Material.ARROW); var meta = item.getItemMeta(); meta.displayName(Component.text(name)); item.setItemMeta(meta); return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot == 45 && menu.page > 0) { open(player, menu.kits, menu.page - 1); return; }
        if (slot == 53 && (menu.page + 1) * 45 < menu.kits.size()) { open(player, menu.kits, menu.page + 1); return; }
        int index = menu.page * 45 + slot;
        if (slot < 0 || slot >= 45 || index >= menu.kits.size()) return;
        player.closeInventory();
        if (matching.contains(player.getUniqueId())) { player.sendMessage("PvP-Match wird bereits vorbereitet"); return; }
        queue.join(player.getUniqueId(), menu.kits.get(index).name());
        player.sendMessage("PvP-Queue: Platz " + queue.position(player.getUniqueId()));
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { queue.leave(event.getPlayer().getUniqueId()); }
    private void tick() {
        for (UUID id : matching) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendActionBar(Component.text("PvP-Server startet ..."));
        }
        Optional<PvpQueue.Pair> next;
        while ((next = queue.poll()).isPresent()) {
            PvpQueue.Pair pair = next.orElseThrow();
            matching.add(pair.a()); matching.add(pair.b());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> prepare(pair));
        }
    }
    private void prepare(PvpQueue.Pair pair) {
        String id = UUID.randomUUID().toString();
        try (Connection c = source.open(); PreparedStatement s = c.prepareStatement("INSERT INTO pvp_assignments (match_id,player_a,player_b,kit_a,kit_b,status,created_at,updated_at) VALUES (?,?,?,?,?,'pending',?,?)")) {
            long now = System.currentTimeMillis();
            s.setString(1, id); s.setString(2, pair.a().toString()); s.setString(3, pair.b().toString());
            s.setString(4, pair.kitA()); s.setString(5, pair.kitB()); s.setLong(6, now); s.setLong(7, now); s.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("PvP-Zuweisung fehlgeschlagen: " + e.getClass().getSimpleName());
            Bukkit.getScheduler().runTask(plugin, () -> finishFailure(pair)); return;
        }
        if (!host.status().equals("online") && !host.start()) { fail(id, pair, null); return; }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (host.status().equals("online")) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> connect(id, pair), 60L); return;
            }
            try { Thread.sleep(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        fail(id, pair, null);
    }
    private void connect(String id, PvpQueue.Pair pair) {
        Player a = Bukkit.getPlayer(pair.a()), b = Bukkit.getPlayer(pair.b());
        if (a == null || b == null) { UUID remaining = a != null ? pair.a() : b != null ? pair.b() : null; Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> fail(id, pair, remaining)); return; }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF("pvp"); }
            a.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            b.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            matching.remove(pair.a()); matching.remove(pair.b());
        } catch (Exception e) { Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> fail(id, pair, null)); }
    }
    private void fail(String id, PvpQueue.Pair pair, UUID remaining) {
        try { store.markStatus(id, "pending", "failed"); }
        catch (Exception e) { plugin.getLogger().warning("PvP-Zuweisung konnte nicht als fehlgeschlagen markiert werden: " + e.getClass().getSimpleName()); }
        Bukkit.getScheduler().runTask(plugin, () -> {
            finishFailure(pair);
            if (remaining != null && Bukkit.getPlayer(remaining) != null) {
                queue.join(remaining, remaining.equals(pair.a()) ? pair.kitA() : pair.kitB());
                message(remaining, "PvP-Queue: Platz " + queue.position(remaining));
            }
        });
    }
    private void finishFailure(PvpQueue.Pair pair) {
        matching.remove(pair.a()); matching.remove(pair.b());
        message(pair.a(), "PvP-Server nicht erreichbar, bitte spaeter erneut");
        message(pair.b(), "PvP-Server nicht erreichbar, bitte spaeter erneut");
    }
    private void message(UUID id, String text) { Player player = Bukkit.getPlayer(id); if (player != null) player.sendMessage(text); }
}
