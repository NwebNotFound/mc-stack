package de.mcstack.craftcore;

import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Single place that talks to Velocity. Registers the plugin message channels once per plugin and
 * uses the dedicated proxy listener for switches; the byte-array overload retains the legacy channel.
 */
public final class ProxyChannel {
  private ProxyChannel() {}

  /**
   * Told before every switch, so state that would fight it (a pending VIP auto-connect) can step aside.
   * Returns true when this listener sent its own connect a moment ago; Velocity drops a second request while one is
   * still in flight, so the switch is then sent once more after 3 s (bug 4, 05.11.2026).
   */
  public interface SwitchListener { boolean beforeSwitch(Player player, String server); }
  static final SwitchIntent INTENT = new SwitchIntent();
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
    register(plugin, SpawnRoute.DIRECT_CHANNEL);
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
  public static synchronized boolean send(JavaPlugin plugin, Player player, String server) {
    try {
      long token = INTENT.begin(player.getUniqueId());
      return sendFor(plugin, player, server, token);
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

  /** One switch under {@code token}; the delayed resend only fires while that token is still the player's latest choice. */
  private static boolean sendFor(JavaPlugin plugin, Player player, String server, long token) throws IOException {
    boolean recent = notifySwitch(player, server);
    byte[] payload = SpawnRoute.direct(server, token);
    boolean ok = sendMessage(plugin, player, SpawnRoute.DIRECT_CHANNEL, payload);
    java.util.UUID id = player.getUniqueId();
    if (ok && recent) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline() && Bukkit.getPlayer(id) == player && INTENT.current(id, token)) sendMessage(plugin, player, SpawnRoute.DIRECT_CHANNEL, payload); }, 60L);
    return ok;
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
    // send() just began a new intent; verify only that one.
    verifyLater(plugin, player, server, 1, INTENT.latestFor(player.getUniqueId()));
    return true;
  }

  /** Attempt 1 still here after 5 s: send once more from a plain scheduler tick, then tell the player and log. */
  private static void verifyLater(JavaPlugin plugin, Player session, String server, int attempt, long token) {
    java.util.UUID id = session.getUniqueId();
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      Player still = Bukkit.getPlayer(id);
      if (still == null || !still.isOnline()) return; // switched away: fine
      if (still != session) return;                    // reconnected: a new session never gets the old destination
      if (!INTENT.current(id, token)) return;          // a newer choice (another portal, /lobby, VIP) wins
      boolean registered = still.getListeningPluginChannels().contains(SpawnRoute.DIRECT_CHANNEL);
      plugin.getLogger().warning("Proxy hat den Wechsel von " + still.getName() + " nach " + server + " nicht ausgefuehrt (Versuch "
          + attempt + ", Kanal " + SpawnRoute.DIRECT_CHANNEL + " registriert: " + registered + ", alle Kanaele: " + new java.util.TreeSet<>(still.getListeningPluginChannels()) + ")");
      if (attempt == 1) {
        boolean resent;
        try { resent = sendFor(plugin, still, server, token); } catch (IOException e) { resent = false; }
        if (resent) { verifyLater(plugin, session, server, 2, token); return; }
      }
      still.sendMessage(notMovedText(server));
      // A click sends the command from the client, so Velocity runs its own /server, which works (Nils, 05.11.).
      still.sendMessage(net.kyori.adventure.text.Component.text("[Hier klicken: nach " + server + " wechseln]", net.kyori.adventure.text.format.NamedTextColor.GREEN)
          .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/server " + server)));
    }, 100L);
  }

  static String notMovedText(String server) {
    return "Der Wechsel nach " + server + " kam beim Proxy nicht an. Versuch es nochmal oder tippe /server " + server + ".";
  }

  static String failureText(String server) {
    return "Serverwechsel nach " + server + " fehlgeschlagen. Bitte erneut versuchen oder /lobby nutzen.";
  }

  /** @return false when Bukkit refused the message, e.g. an unregistered channel. */
  public static synchronized boolean send(JavaPlugin plugin, Player player, byte[] payload) {
    return sendMessage(plugin, player, SpawnRoute.CHANNEL, payload);
  }

  private static boolean sendMessage(JavaPlugin plugin, Player player, String channel, byte[] payload) {
    try {
      register(plugin);
      ChannelFix.Outcome fix = ChannelFix.ensure(player, player.getListeningPluginChannels(), channel);
      if (fix == ChannelFix.Outcome.ADDED) plugin.getLogger().info("Kanal " + channel + " fuer " + player.getName() + " nachregistriert (Proxy-Register fehlte)");
      else if (fix == ChannelFix.Outcome.UNSUPPORTED) plugin.getLogger().warning("Kanal " + channel + " fehlt fuer " + player.getName() + " und laesst sich nicht nachregistrieren");
      player.sendPluginMessage(plugin, channel, payload);
      return true;
    } catch (RuntimeException e) {
      plugin.getLogger().warning("Proxy-Nachricht fehlgeschlagen: " + e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : " " + e.getMessage()));
      return false;
    }
  }
}
