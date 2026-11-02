package de.mcstack.craftcore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * {@code /lobby}, {@code /l}, {@code /hub}, {@code /leave} and {@code /spawn} on every backend.
 *
 * <p>Nobody registered these names: Velocity has no command plugin for them and the backend only
 * had {@code /spawn} inside the economy module, so the commands were unknown on all four server
 * types. This route belongs to no module, is created for lobby, survival, VIP worlds and PvP, and
 * sends the player to the lobby through Velocity's plugin message channel.</p>
 */
final class LobbyRoute implements Listener {
  private final CraftCore core;
  private final String server;
  private final Map<UUID, Integer> warmups = new HashMap<>();

  LobbyRoute(CraftCore core, String server) {
    this.core = core;
    this.server = server;
  }

  void start() {
    ProxyChannel.register(core);
    Bukkit.getPluginManager().registerEvents(this, core);
  }

  /** {@code name} must pass {@link SpawnRoute#isLobbyCommand(String)}. */
  boolean run(CommandSender sender, String name) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(CraftCore.PLAYER_ONLY_MESSAGE);
      return true;
    }
    startWarmup(player);
    return true;
  }

  /** Same warmup the teleports use: no movement, no damage, message every second. */
  void startWarmup(Player player) {
    cancel(player);
    int[] left = {Math.max(1, core.getConfig().getInt("teleport-seconds", 10))};
    player.sendMessage("Teleport in " + left[0] + " Sekunden. Stillstehen!");
    int task = Bukkit.getScheduler().scheduleSyncRepeatingTask(core, () -> {
      if (!player.isOnline()) {
        cancel(player);
        return;
      }
      if (--left[0] <= 0) {
        cancel(player);
        goLobby(player);
        return;
      }
      player.sendMessage("Teleport in " + left[0] + " Sekunden.");
    }, 20L, 20L);
    warmups.put(player.getUniqueId(), task);
  }

  void goLobby(Player player) {
    if (SpawnRoute.isLobby(server)) {
      if (!Bukkit.getWorlds().isEmpty()) player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
      return;
    }
    markArrival(player.getUniqueId());
    ProxyChannel.send(core, player, SpawnRoute.LOBBY);
  }

  /**
   * Tells the lobby that this join is a lobby command, so it lands on the lobby spawn instead of
   * the logout position. Best effort: a server without the shared market tables simply skips it.
   */
  private void markArrival(UUID id) {
    try {
      long expires = System.currentTimeMillis() + 30_000L;
      core.tx(c -> {
        core.exec(c, "REPLACE INTO spawn_transfers(uuid,expires) VALUES (?,?)", id.toString(), expires);
        return null;
      });
    } catch (Exception e) {
      core.getLogger().info("Spawn-Markierung nicht gesetzt: " + e.getClass().getSimpleName());
    }
  }

  void cancel(Player player) {
    Integer task = warmups.remove(player.getUniqueId());
    if (task != null) Bukkit.getScheduler().cancelTask(task);
  }

  @EventHandler
  public void move(PlayerMoveEvent event) {
    if (!warmups.containsKey(event.getPlayer().getUniqueId())) return;
    Location from = event.getFrom(), to = event.getTo();
    if (to == null || from == null) return;
    if (!Objects.equals(from.getWorld(), to.getWorld())
        || WarmupMove.blockChanged(from.getBlockX(), from.getBlockY(), from.getBlockZ(),
            to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
      cancel(event.getPlayer());
      event.getPlayer().sendMessage("Teleport abgebrochen.");
    }
  }

  @EventHandler
  public void damage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player player) || !warmups.containsKey(player.getUniqueId())) return;
    cancel(player);
    player.sendMessage("Teleport abgebrochen.");
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) { cancel(event.getPlayer()); }
}
