package de.mcstack.craftcore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Portal regions (config {@code portals.<name>}): entering the box sends the player to a Velocity server
 * via the BungeeCord plugin channel ("Connect"), with a per-player cooldown.
 * Config: world, min [x,y,z], max [x,y,z], target (server), optional message, cooldown-seconds (default 5).
 */
final class Portals implements Listener, CommandExecutor {
  record Region(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2, String target, String message, long cooldownMillis) {
    boolean contains(String w, int x, int y, int z) {
      return world.equals(w) && x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }
    String describe() { return name + ": " + world + " " + x1 + "," + y1 + "," + z1 + " .. " + x2 + "," + y2 + "," + z2 + " -> " + target + " (Cooldown " + cooldownMillis / 1000 + " s)"; }
  }

  /** Parse one portal; null when invalid. Corners are normalized (min/max per axis). */
  static Region parse(String name, String world, List<Integer> a, List<Integer> b, String target, String message, double cooldownSeconds) {
    if (world == null || world.isBlank() || a == null || b == null || a.size() != 3 || b.size() != 3) return null;
    if (target == null || !target.matches("[A-Za-z0-9_-]{1,32}")) return null;
    long cd = (long) (Math.max(1, cooldownSeconds) * 1000);
    return new Region(name, world, Math.min(a.get(0), b.get(0)), Math.min(a.get(1), b.get(1)), Math.min(a.get(2), b.get(2)),
      Math.max(a.get(0), b.get(0)), Math.max(a.get(1), b.get(1)), Math.max(a.get(2), b.get(2)), target, message, cd);
  }

  static byte[] connectMessage(String target) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(target); }
    return bytes.toByteArray();
  }

  /** Cooldown check: true and remembered when allowed. */
  static boolean admit(Map<UUID, Long> last, UUID id, long now, long cooldownMillis) {
    Long prev = last.get(id);
    if (prev != null && now - prev < cooldownMillis) return false;
    last.put(id, now);
    return true;
  }

  private final CraftCore core;
  private volatile List<Region> regions = List.of();
  private final Map<UUID, Long> last = new HashMap<>();

  Portals(CraftCore core) { this.core = core; }

  void start() {
    load();
    Bukkit.getMessenger().registerOutgoingPluginChannel(core, "BungeeCord");
    Bukkit.getPluginManager().registerEvents(this, core);
    var cmd = core.getCommand("ccportal");
    if (cmd != null) cmd.setExecutor(this);
  }

  void load() {
    List<Region> out = new ArrayList<>();
    ConfigurationSection root = core.getConfig().getConfigurationSection("portals");
    if (root != null) for (String name : root.getKeys(false)) {
      ConfigurationSection s = root.getConfigurationSection(name);
      if (s == null) continue;
      Region r = parse(name, s.getString("world"), s.getIntegerList("min"), s.getIntegerList("max"), s.getString("target"), s.getString("message", "Portal: Verbinde mit %s ..."), s.getDouble("cooldown-seconds", 5));
      if (r == null) core.getLogger().warning("Portal " + name + " ungueltig, ignoriert");
      else out.add(r);
    }
    regions = List.copyOf(out);
    core.getLogger().info("Portale: " + regions.size() + (regions.isEmpty() ? "" : " (" + String.join("; ", regions.stream().map(Region::describe).toList()) + ")"));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    if (regions.isEmpty()) return;
    Location to = e.getTo(), from = e.getFrom();
    if (to == null || (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ() && to.getWorld() == from.getWorld())) return;
    String w = to.getWorld().getName();
    for (Region r : regions) {
      if (!r.contains(w, to.getBlockX(), to.getBlockY(), to.getBlockZ())) continue;
      if (r.contains(from.getWorld().getName(), from.getBlockX(), from.getBlockY(), from.getBlockZ())) return;
      send(e.getPlayer(), r);
      return;
    }
  }

  @EventHandler public void quit(PlayerQuitEvent e) { last.remove(e.getPlayer().getUniqueId()); }

  boolean send(Player p, Region r) {
    if (!admit(last, p.getUniqueId(), System.currentTimeMillis(), r.cooldownMillis())) return false;
    try {
      if (r.message() != null && !r.message().isBlank()) p.sendMessage(String.format(Locale.ROOT, r.message(), r.target()));
      p.sendPluginMessage(core, "BungeeCord", connectMessage(r.target()));
      core.getLogger().info("Portal " + r.name() + ": " + p.getName() + " -> " + r.target());
      return true;
    } catch (Exception ex) {
      core.getLogger().warning("Portal " + r.name() + " fehlgeschlagen: " + ex.getClass().getSimpleName());
      p.sendMessage("Serverwechsel fehlgeschlagen. Bitte erneut versuchen.");
      return false;
    }
  }

  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (sender instanceof Player p && !p.hasPermission("craftcore.portal.admin")) { sender.sendMessage("Keine Berechtigung."); return true; }
    String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "list" -> { if (regions.isEmpty()) sender.sendMessage("Keine Portale."); regions.forEach(r -> sender.sendMessage(r.describe())); }
      case "reload" -> { core.reloadConfig(); load(); sender.sendMessage("Portale neu geladen: " + regions.size()); }
      case "send" -> {
        if (args.length != 3) { sender.sendMessage("/ccportal send <spieler> <portal>"); return true; }
        Player p = Bukkit.getPlayerExact(args[1]);
        Region r = regions.stream().filter(x -> x.name().equalsIgnoreCase(args[2])).findFirst().orElse(null);
        if (p == null || r == null) { sender.sendMessage("Spieler oder Portal nicht gefunden."); return true; }
        last.remove(p.getUniqueId());
        sender.sendMessage(send(p, r) ? "OK" : "ERR");
      }
      default -> sender.sendMessage("/ccportal list|reload|send <spieler> <portal>");
    }
    return true;
  }
}
