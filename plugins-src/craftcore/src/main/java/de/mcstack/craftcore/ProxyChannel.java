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
  }

  private static void register(JavaPlugin plugin, String channel) {
    if (!Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, channel)) {
      Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
    }
  }

  /**
   * Sends a player to another backend.
   *
   * @return false when the proxy message could not be sent; the caller decides what happens next
   *         (kick, retry or a chat hint).
   */
  public static boolean send(JavaPlugin plugin, Player player, String server) {
    try {
      return send(plugin, player, SpawnRoute.connect(server));
    } catch (IOException e) {
      plugin.getLogger().warning("Proxy-Wechsel nach " + server + " fehlgeschlagen: " + e.getMessage());
      return false;
    } catch (RuntimeException e) {
      // An unregistered channel or a refused messenger throws here; the caller still gets false.
      plugin.getLogger().warning("Proxy-Wechsel nach " + server + " fehlgeschlagen: "
          + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()));
      return false;
    }
  }

  /** Like {@link #send(JavaPlugin, Player, String)}, but tells the player in chat when the switch could not be sent. */
  public static boolean sendOrTell(JavaPlugin plugin, Player player, String server) {
    if (send(plugin, player, server)) return true;
    player.sendMessage(failureText(server));
    return false;
  }

  static String failureText(String server) {
    return "Serverwechsel nach " + server + " fehlgeschlagen. Bitte erneut versuchen oder /lobby nutzen.";
  }

  /** @return false when Bukkit refused the message, e.g. an unregistered channel. */
  public static boolean send(JavaPlugin plugin, Player player, byte[] payload) {
    try {
      register(plugin);
      player.sendPluginMessage(plugin, SpawnRoute.CHANNEL, payload);
      return true;
    } catch (RuntimeException e) {
      plugin.getLogger().warning("Proxy-Nachricht fehlgeschlagen: " + e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : " " + e.getMessage()));
      return false;
    }
  }
}
