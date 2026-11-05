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

  /**
   * Told before every switch, so state that would fight it (a pending VIP auto-connect) can step aside.
   * Returns true when this listener sent its own connect a moment ago; Velocity drops a second request while one is
   * still in flight, so the switch is then sent once more after 3 s (bug 4, 05.11.2026).
   */
  public interface SwitchListener { boolean beforeSwitch(Player player, String server); }
  private static final java.util.List<SwitchListener> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
  public static void onSwitch(SwitchListener listener) { LISTENERS.add(listener); }
  static boolean notifySwitch(Player player, String server) {
    boolean recent = false;
    for (SwitchListener l : LISTENERS) {
      try { recent |= l.beforeSwitch(player, server); } catch (RuntimeException ignored) {}
    }
    return recent;
  }

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
      boolean recent = notifySwitch(player, server);
      byte[] payload = SpawnRoute.connect(server);
      boolean ok = send(plugin, player, payload);
      if (ok && recent) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline()) send(plugin, player, payload); }, 60L);
      return ok;
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

  /**
   * Like {@link #sendOrTell}, and 5 s later checks that the proxy actually moved the player. Velocity drops a
   * Connect silently in several cases (a cancelled pre-connect, a request already in flight), and Paper drops a
   * message on a channel the connection has not registered; the player then stood in the portal without a word
   * (05.11.2026, Survival portal). Now they get a chat line with the /server fallback, and the log says so.
   */
  public static boolean sendVerified(JavaPlugin plugin, Player player, String server) {
    if (!sendOrTell(plugin, player, server)) return false;
    verifyLater(plugin, player.getUniqueId(), server, 1);
    return true;
  }

  /** Attempt 1 still here after 5 s: send once more from a plain scheduler tick, then tell the player and log. */
  private static void verifyLater(JavaPlugin plugin, java.util.UUID id, String server, int attempt) {
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      Player still = Bukkit.getPlayer(id);
      if (still == null || !still.isOnline()) return; // switched away: fine
      boolean registered = still.getListeningPluginChannels().contains(SpawnRoute.CHANNEL);
      plugin.getLogger().warning("Proxy hat den Wechsel von " + still.getName() + " nach " + server + " nicht ausgefuehrt (Versuch "
          + attempt + ", Kanal " + SpawnRoute.CHANNEL + " registriert: " + registered + ")");
      if (attempt == 1 && send(plugin, still, server)) { verifyLater(plugin, id, server, 2); return; }
      still.sendMessage(notMovedText(server));
    }, 100L);
  }

  static String notMovedText(String server) {
    return "Der Wechsel nach " + server + " kam beim Proxy nicht an. Versuch es nochmal oder tippe /server " + server + ".";
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
