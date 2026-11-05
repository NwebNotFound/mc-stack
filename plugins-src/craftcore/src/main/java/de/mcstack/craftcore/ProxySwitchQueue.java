package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Second path for server switches: a row in {@code proxy_switch_requests} that our Velocity plugin
 * (plugins-src/server-status-velocity, SwitchDecision) executes if the bungeecord:main plugin message did not move the
 * player within a few seconds. Only the latest request per player counts; a quit cancels it.
 */
final class ProxySwitchQueue {
  private static volatile boolean warned;

  private ProxySwitchQueue() {}

  static void migrate(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS proxy_switch_requests (id BIGINT AUTO_INCREMENT PRIMARY KEY, player CHAR(36) NOT NULL, server VARCHAR(32) NOT NULL, source VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, done_at BIGINT NULL, result VARCHAR(16) NULL, INDEX pending_switch (done_at, created_at), INDEX switch_player (player, done_at))");
    }
  }

  /** Append only: publishing a newer choice must never wait on an older candidate's row lock. */
  static void enqueue(Connection c, UUID player, String server, String source, long now) throws SQLException {
    try (PreparedStatement add = c.prepareStatement("INSERT INTO proxy_switch_requests(player, server, source, created_at) VALUES(?,?,?,?)")) {
      add.setString(1, player.toString()); add.setString(2, server); add.setString(3, source); add.setLong(4, now); add.executeUpdate();
    }
  }

  static void cancel(Connection c, UUID player, long now) throws SQLException {
    try (PreparedStatement q=c.prepareStatement("SELECT MAX(created_at) FROM proxy_switch_requests WHERE player=?")) {
      q.setString(1, player.toString());
      try (var r=q.executeQuery()) { if (r.next() && r.getLong(1)>now) return; }
    }
    enqueue(c, player, "", "", now);
  }

  /** Publish before returning to the player event; a backend whose DB user cannot write the table (VIP worlds) simply keeps the plugin-message path. */
  static void publish(CraftCore core, UUID player, String server, String source, long token) {
    long now = System.currentTimeMillis();
    if (!ProxyChannel.INTENT.current(player, token)) return;
    try { core.tx(c -> { enqueue(c, player, server, source, now); return null; }); }
    catch (Exception e) { if (!warned) { warned = true; core.getLogger().warning("Wechsel-Queue nicht verfuegbar: " + e.getMessage()); } }
  }

  static void cancelPublished(CraftCore core, UUID player) {
    long now = System.currentTimeMillis();

    try { core.tx(c -> { cancel(c, player, now); return null; }); } catch (Exception ignored) {}
  }
}
