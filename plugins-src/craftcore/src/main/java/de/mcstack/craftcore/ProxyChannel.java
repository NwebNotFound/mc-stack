package de.mcstack.craftcore;

import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Single place that talks to Velocity. Registers the plugin message channels once per plugin and
 * always sends on {@link SpawnRoute#CHANNEL}, the name the proxy actually reads.
 */
public final class ProxyChannel {
  private ProxyChannel() {}

  /** Safe to call from several modules: an already registered channel is not registered twice. */
  public static void register(JavaPlugin plugin) {
    register(plugin, SpawnRoute.CHANNEL);
    register(plugin, SpawnRoute.LEGACY_CHANNEL);
  }

  private static void register(JavaPlugin plugin, String channel) {
    if (!Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, channel)) {
      Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
    }
  }

  /** Sends a player to another backend of the network. */
  public static void send(JavaPlugin plugin, Player player, String server) {
    try {
      send(plugin, player, SpawnRoute.connect(server));
    } catch (IOException e) {
      plugin.getLogger().warning("Proxy-Wechsel nach " + server + " fehlgeschlagen: " + e.getMessage());
      player.sendMessage("Verbindung zu " + server + " fehlgeschlagen.");
    }
  }

  public static void send(JavaPlugin plugin, Player player, byte[] payload) {
    register(plugin);
    player.sendPluginMessage(plugin, SpawnRoute.CHANNEL, payload);
  }
}
