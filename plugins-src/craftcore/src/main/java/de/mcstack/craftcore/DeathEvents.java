package de.mcstack.craftcore;

import java.io.File;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.projectiles.ProjectileSource;

/** Death events and an immutable, per-player historical baseline from vanilla stats. */
final class DeathEvents implements Listener {
  record Death(String id, String uuid, long ts, String cause, String killer, String entity, String block, String server) {}
  private final CraftCore core;
  private final String server;
  private final ExecutorService writer=Executors.newSingleThreadExecutor();
  DeathEvents(CraftCore core,String server) { this.core=core; this.server=server.toLowerCase(Locale.ROOT); }
  static void migrate(Connection c) throws SQLException {
    try(Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS player_deaths (event_id CHAR(36) PRIMARY KEY, uuid CHAR(36) NOT NULL, ts BIGINT NOT NULL, cause_type VARCHAR(64) NOT NULL, killer VARCHAR(64), entity_type VARCHAR(64), block VARCHAR(64), server VARCHAR(32) NOT NULL, INDEX death_player(uuid,server,ts))");
      s.execute("CREATE TABLE IF NOT EXISTS player_death_backfill (uuid CHAR(36) NOT NULL, server VARCHAR(32) NOT NULL, stat_key VARCHAR(64) NOT NULL, stat_value BIGINT NOT NULL, PRIMARY KEY(uuid,server,stat_key))");
      s.execute("CREATE TABLE IF NOT EXISTS player_death_backfill_done (uuid CHAR(36) NOT NULL, server VARCHAR(32) NOT NULL, PRIMARY KEY(uuid,server))");
      s.execute("CREATE OR REPLACE VIEW deaths_by AS SELECT uuid,stat_key AS `key`,SUM(n) AS `value` FROM (SELECT uuid,LOWER(COALESCE(entity_type,cause_type)) stat_key,COUNT(*) n FROM player_deaths GROUP BY uuid,entity_type,cause_type UNION ALL SELECT uuid,stat_key,stat_value FROM player_death_backfill) d GROUP BY uuid,stat_key");
    }
  }
  static void insert(Connection c,Death d) throws SQLException {
    try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO player_deaths(event_id,uuid,ts,cause_type,killer,entity_type,block,server) VALUES(?,?,?,?,?,?,?,?)")) {
      q.setString(1,d.id()); q.setString(2,d.uuid()); q.setLong(3,d.ts()); q.setString(4,d.cause()); q.setString(5,d.killer()); q.setString(6,d.entity()); q.setString(7,d.block()); q.setString(8,d.server()); q.executeUpdate();
    }
  }
  void start() throws Exception { core.tx(c->{migrate(c);return null;}); Bukkit.getPluginManager().registerEvents(this,core); }
  @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent event) {
    Player p=event.getEntity(); EntityDamageEvent damage=p.getLastDamageCause();
    String cause=damage==null?"UNKNOWN":damage.getCause().name(), killer=null, entity=null, block=null;
    if(damage instanceof EntityDamageByEntityEvent by) {
      Entity source=by.getDamager();
      if(source instanceof Projectile projectile) { ProjectileSource shooter=projectile.getShooter(); if(shooter instanceof Entity e) source=e; }
      entity=source.getType().name(); killer=source instanceof Player ? source.getUniqueId().toString() : entity;
    }
    if(damage instanceof EntityDamageByBlockEvent by && by.getDamager()!=null) block=by.getDamager().getType().name();
    Death d=new Death(UUID.randomUUID().toString(),p.getUniqueId().toString(),System.currentTimeMillis(),cause,killer,entity,block,server);
    writer.execute(()->{try {core.tx(c->{insert(c,d);return null;});} catch(Exception ex){core.getLogger().severe("Death-Event speichern fehlgeschlagen: "+ex);}});
  }
  static Map<String,Long> baseline(long total,Map<String,Long> vanilla,Map<String,Long> recorded) {
    Map<String,Long> result=new TreeMap<>(); long remaining=Math.max(0,total-recorded.values().stream().mapToLong(Long::longValue).sum());
    for(var entry:new TreeMap<>(vanilla).entrySet()) { String k=entry.getKey().toLowerCase(Locale.ROOT); long n=Math.min(remaining,Math.max(0,entry.getValue()-recorded.getOrDefault(k,0L))); if(n>0){result.put(k,n);remaining-=n;} }
    if(remaining>0) result.merge("unknown",remaining,Long::sum);
    return result;
  }
  static boolean backfillOne(Connection c,String uuid,String server,long cutoff,long total,Map<String,Long> vanilla) throws SQLException {
    try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO player_death_backfill_done(uuid,server) VALUES(?,?)")){q.setString(1,uuid);q.setString(2,server);if(q.executeUpdate()==0)return false;}
    Map<String,Long> recorded=new HashMap<>();
    try(PreparedStatement q=c.prepareStatement("SELECT LOWER(COALESCE(entity_type,cause_type)),COUNT(*) FROM player_deaths WHERE uuid=? AND server=? AND ts<=? GROUP BY entity_type,cause_type")){q.setString(1,uuid);q.setString(2,server);q.setLong(3,cutoff);try(ResultSet r=q.executeQuery()){while(r.next())recorded.merge(r.getString(1),r.getLong(2),Long::sum);}}
    try(PreparedStatement q=c.prepareStatement("INSERT INTO player_death_backfill(uuid,server,stat_key,stat_value) VALUES(?,?,?,?)")){for(var e:baseline(total,vanilla,recorded).entrySet()){q.setString(1,uuid);q.setString(2,server);q.setString(3,e.getKey());q.setLong(4,e.getValue());q.addBatch();}q.executeBatch();} return true;
  }
  void backfill(CommandSender sender) {
    if(!SpawnRoute.isSurvival(server)){sender.sendMessage("Backfill bitte auf Survival ausfuehren.");return;}
    File dir=new File(Bukkit.getWorlds().get(0).getWorldFolder(),"stats");
    if(!dir.isDirectory()) { File alternate=new File(Bukkit.getWorlds().get(0).getWorldFolder(),"players/stats"); if(alternate.isDirectory()) dir=alternate; }
    Set<String> online=new HashSet<>(); Bukkit.getOnlinePlayers().forEach(p->online.add(p.getUniqueId().toString()));
    final File stats=dir; sender.sendMessage("Death-Backfill gestartet: "+stats+" (Online-Spieler ausgenommen; nach Quit erneut ausfuehren)");
    writer.execute(()->{int n=0,errors=0; File[] files=stats.listFiles((d,name)->name.endsWith(".json"));
      if(files==null){core.getLogger().warning("Death-Backfill: Stats-Verzeichnis nicht lesbar: "+stats); return;}
      for(File f:files) try { String uuid=UUID.fromString(f.getName().substring(0,f.getName().length()-5)).toString(); if(online.contains(uuid)) continue; long cutoff=f.lastModified();
        Object[] parsed=PlayerStats.parseStats(UUID.fromString(uuid),Files.readString(f.toPath()),true); PlayerStats.Snapshot snap=(PlayerStats.Snapshot)parsed[1];
        @SuppressWarnings("unchecked") List<PlayerStats.Detail> details=(List<PlayerStats.Detail>)parsed[2]; Map<String,Long> vanilla=new HashMap<>(); for(var d:details) if(d.category().equals("deaths_entity")) vanilla.merge(d.key().toLowerCase(Locale.ROOT),d.value(),Long::sum);
        if(core.tx(c->backfillOne(c,uuid,server,cutoff,snap.deaths(),vanilla))) n++;
      } catch(Exception ex){errors++;core.getLogger().warning("Death-Backfill "+f.getName()+": "+ex);}
      core.getLogger().info("Death-Backfill: "+n+" Spieler, "+errors+" Fehler");
    });
  }
  void shutdown(){writer.shutdown();try{if(!writer.awaitTermination(30,TimeUnit.SECONDS))core.getLogger().warning("Death-Speicherung noch nicht abgeschlossen");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
