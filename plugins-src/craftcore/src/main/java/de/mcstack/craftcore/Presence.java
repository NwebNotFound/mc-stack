package de.mcstack.craftcore;

import java.sql.*;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Shared, per-backend presence. DATETIME values are written explicitly in UTC. */
final class Presence implements Listener {
  static final String DDL = "CREATE TABLE IF NOT EXISTS player_presence (uuid CHAR(36) NOT NULL, server VARCHAR(64) NOT NULL, last_seen DATETIME(6) NOT NULL, online BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY (uuid,server))";
  static final String RECORD_SQL = "INSERT INTO player_presence(uuid,server,last_seen,online) VALUES(?,?,UTC_TIMESTAMP(6),?) ON DUPLICATE KEY UPDATE last_seen=VALUES(last_seen),online=VALUES(online)";
  interface Store { void write(UUID uuid, boolean online) throws Exception; void reset() throws Exception; }
  static final class Tracker {
    private final Store store;
    Tracker(Store store) { this.store=store; }
    void start() throws Exception { store.reset(); }
    void join(UUID uuid) throws Exception { store.write(uuid,true); }
    void quit(UUID uuid) throws Exception { store.write(uuid,false); }
    void stop() throws Exception { store.reset(); }
  }
  private final CraftCore core;
  private final String server;
  private final Tracker tracker;
  Presence(CraftCore core,String server) {
    this.core=core; this.server=server.toLowerCase(Locale.ROOT);
    tracker=new Tracker(new Store() {
      public void write(UUID uuid,boolean online) throws SQLException { try(Connection c=core.connection()) { record(c,uuid,Presence.this.server,online); } }
      public void reset() throws SQLException { try(Connection c=core.connection()) { resetBackend(c,Presence.this.server); } }
    });
  }
  static void migrate(Connection c) throws SQLException { try(Statement s=c.createStatement()) { s.execute(DDL); } }
  static void record(Connection c,UUID uuid,String server,boolean online) throws SQLException {
    try(PreparedStatement q=c.prepareStatement(RECORD_SQL)) {
      q.setString(1,uuid.toString()); q.setString(2,server); q.setBoolean(3,online); q.executeUpdate();
    }
  }
  static void resetBackend(Connection c,String server) throws SQLException {
    try(PreparedStatement q=c.prepareStatement("UPDATE player_presence SET online=FALSE WHERE server=? AND online=TRUE")) { q.setString(1,server); q.executeUpdate(); }
  }
  void start(boolean migrate) throws Exception {
    if(migrate) try(Connection c=core.connection()) { migrate(c); }
    tracker.start();
    Bukkit.getPluginManager().registerEvents(this,core);
    for(var player:Bukkit.getOnlinePlayers()) tracker.join(player.getUniqueId());
  }
  void shutdown() { try { tracker.stop(); } catch(Exception e) { core.getLogger().warning("Presence shutdown: "+e); } }
  @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent event) { try { tracker.join(event.getPlayer().getUniqueId()); } catch(Exception e) { core.getLogger().warning("Presence join: "+e); } }
  @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { try { tracker.quit(event.getPlayer().getUniqueId()); } catch(Exception e) { core.getLogger().warning("Presence quit: "+e); } }
}
