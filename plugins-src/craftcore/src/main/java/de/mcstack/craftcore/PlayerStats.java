package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;

/** Snapshots Bukkit statistics on the server thread and serializes DB writes off-thread. */
final class PlayerStats implements Listener {
  record Snapshot(UUID uuid, long playtime, long kills, long deaths, long blocks) {}
  record Detail(String category, String key, long value) {}
  private static final List<String> DISTANCE_STATS=List.of("WALK_ONE_CM","SPRINT_ONE_CM","CROUCH_ONE_CM","SWIM_ONE_CM","WALK_UNDER_WATER_ONE_CM","WALK_ON_WATER_ONE_CM","FLY_ONE_CM","AVIATE_ONE_CM","BOAT_ONE_CM","MINECART_ONE_CM","HORSE_ONE_CM","PIG_ONE_CM","STRIDER_ONE_CM","CLIMB_ONE_CM","FALL_ONE_CM");
  private final CraftCore core;
  private final String server;
  private final ExecutorService writer = Executors.newSingleThreadExecutor();
  PlayerStats(CraftCore core, String server) { this.core = core; this.server = server; }
  static void migrate(Connection c) throws SQLException {
    try (Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS player_playtime_world (uuid CHAR(36) NOT NULL, server VARCHAR(16) NOT NULL, playtime_s BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(uuid,server))");
      s.execute("CREATE TABLE IF NOT EXISTS player_stats (uuid CHAR(36) PRIMARY KEY, playtime_s BIGINT NOT NULL, kills BIGINT NOT NULL, deaths BIGINT NOT NULL, blocks_mined BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
      s.execute("CREATE TABLE IF NOT EXISTS player_stats_details (uuid CHAR(36) NOT NULL, category VARCHAR(32) NOT NULL, stat_key VARCHAR(64) NOT NULL, stat_value BIGINT NOT NULL, PRIMARY KEY(uuid,category,stat_key))");
    }
  }
  // The caller owns the transaction: readers must never see a partially replaced snapshot.
  static void replaceDetails(Connection c, UUID uuid, List<Detail> details) throws SQLException {
    try (PreparedStatement d=c.prepareStatement("DELETE FROM player_stats_details WHERE uuid=?")) { d.setString(1,uuid.toString()); d.executeUpdate(); }
    try (PreparedStatement q=c.prepareStatement("INSERT INTO player_stats_details(uuid,category,stat_key,stat_value) VALUES(?,?,?,?)")) {
      for (Detail detail:details) { q.setString(1,uuid.toString()); q.setString(2,detail.category()); q.setString(3,detail.key()); q.setLong(4,detail.value()); q.addBatch(); }
      q.executeBatch();
    }
  }
  static List<Detail> details(Player p) {
    List<Detail> result=new ArrayList<>();
    for(Material m:Material.values()) if(m.isBlock() && !m.isLegacy()) { int n=p.getStatistic(Statistic.MINE_BLOCK,m); if(n>0) result.add(new Detail("blocks_mined",m.name(),n)); }
    for(String key:DISTANCE_STATS) {
      try { int n=p.getStatistic(Statistic.valueOf(key)); if(n>0) result.add(new Detail("distance_cm",key,n)); }
      catch(IllegalArgumentException ignored) { /* Statistic is absent in this server version. */ }
    }
    for(EntityType type:EntityType.values()) if(type.isAlive()) {
      for(Statistic stat:List.of(Statistic.KILL_ENTITY,Statistic.ENTITY_KILLED_BY)) {
        try { int n=p.getStatistic(stat,type); if(n>0) result.add(new Detail(stat==Statistic.KILL_ENTITY?"kills_entity":"deaths_entity",type.name(),n)); }
        catch(IllegalArgumentException ignored) { /* Some entity types have no Bukkit statistic. */ }
      }
    }
    return List.copyOf(result);
  }
  static void upsert(Connection c, Snapshot v) throws SQLException {
    try (PreparedStatement q=c.prepareStatement("INSERT INTO player_stats(uuid,playtime_s,kills,deaths,blocks_mined,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE playtime_s=VALUES(playtime_s),kills=VALUES(kills),deaths=VALUES(deaths),blocks_mined=VALUES(blocks_mined),updated_at=VALUES(updated_at)")) {
      q.setString(1,v.uuid().toString()); q.setLong(2,v.playtime()); q.setLong(3,v.kills()); q.setLong(4,v.deaths()); q.setLong(5,v.blocks()); q.executeUpdate();
    }
  }
  static void upsertPlaytime(Connection c, UUID uuid, String server, long seconds) throws SQLException {
    try (PreparedStatement q=c.prepareStatement("INSERT INTO player_playtime_world(uuid,server,playtime_s,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE playtime_s=VALUES(playtime_s),updated_at=VALUES(updated_at)")) {
      q.setString(1,uuid.toString()); q.setString(2,server); q.setLong(3,seconds); q.executeUpdate();
    }
  }
  static Snapshot snapshot(Player p) {
    long blocks=0;
    for (Material m : Material.values()) if (m.isBlock() && !m.isLegacy()) blocks += p.getStatistic(Statistic.MINE_BLOCK,m);
    return new Snapshot(p.getUniqueId(), p.getStatistic(Statistic.PLAY_ONE_MINUTE)/20L, p.getStatistic(Statistic.PLAYER_KILLS), p.getStatistic(Statistic.DEATHS), blocks);
  }
  void start() throws Exception {
    core.tx(c->{migrate(c); return null;});
    Bukkit.getPluginManager().registerEvents(this,core);
    Bukkit.getScheduler().runTaskTimer(core, ()->Bukkit.getOnlinePlayers().forEach(this::capture), 6000L, 6000L);
  }
  private void capture(Player p) {
    UUID uuid=p.getUniqueId();
    long playtime=p.getStatistic(Statistic.PLAY_ONE_MINUTE)/20L;
    Snapshot v=SpawnRoute.isSurvival(server) ? snapshot(p) : null;
    List<Detail> detail=v==null ? List.of() : details(p);
    writer.execute(()->{ try { core.tx(c->{upsertPlaytime(c,uuid,server,playtime); if(v!=null) { upsert(c,v); replaceDetails(c,uuid,detail); } return null;}); } catch(Exception e) { core.getLogger().warning("Statistik speichern fehlgeschlagen: " + e.getClass().getSimpleName()); } });
  }
  @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { capture(e.getPlayer()); }
  void shutdown() {
    Bukkit.getOnlinePlayers().forEach(this::capture);
    writer.shutdown();
    try { if (!writer.awaitTermination(30,TimeUnit.SECONDS)) core.getLogger().warning("Statistik-Speicherung noch nicht abgeschlossen"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
