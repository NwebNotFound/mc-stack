package de.mcstack.craftcore;

import java.sql.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Database-backed website statistics and market history.
 * The money leaderboard uses the account balance in CP. Period all is that balance.
 * Period week subtracts the first snapshot of the latest stored Berlin week, so it is
 * the net balance change, not gross CP earned and not the balance itself.
 */
final class WebsiteStats {
  static final String LEADERBOARD_VALUES_SQL = "SELECT 'money' board,uuid,mc_name name,COALESCE(money,0) value FROM v_player_stats_ext UNION ALL SELECT 'playtime',uuid,mc_name,playtime FROM v_player_stats_ext UNION ALL SELECT 'blocks_mined',uuid,mc_name,COALESCE(blocks_mined,0) FROM v_player_stats_ext UNION ALL SELECT 'kills',uuid,mc_name,COALESCE(kills,0) FROM v_player_stats_ext UNION ALL SELECT 'kd',uuid,mc_name,kd FROM v_player_stats_ext UNION ALL SELECT 'distance',uuid,mc_name,distance_m FROM v_player_stats_ext";
  static final String LEADERBOARDS_SQL = "SELECT board,period,ROW_NUMBER() OVER (PARTITION BY board,period ORDER BY value DESC,uuid) AS `rank`,uuid,name,value FROM (SELECT board,'all' period,uuid,name,value FROM v_leaderboard_values UNION ALL SELECT v.board,'week',v.uuid,v.name,v.value-COALESCE((SELECT s.value FROM leaderboard_snapshots s WHERE s.board=v.board AND s.uuid=v.uuid AND s.week_ts=(SELECT MAX(week_ts) FROM leaderboard_snapshots) ORDER BY s.day_key LIMIT 1),0) FROM v_leaderboard_values v) ranked";
  /** 24h-Aenderung; vergleicht nur mit Kursen ab dem Preis-Stichtag (price_meta), davor NULL (= neu). */
  static final String CHANGE_24H_SQL = "CASE WHEN pm.effective_from_epoch IS NOT NULL AND h.hour_ts < pm.effective_from_epoch THEN NULL ELSE (c.sell_price-h.avg_price)/NULLIF(h.avg_price,0)*100 END";
  // Keep cause keys distinct from entity IDs even if a damage cause shares an entity name.
  static String entityIdsSql(java.util.stream.Stream<String> ids) { return ids.filter(id -> !id.equalsIgnoreCase("UNKNOWN")).map(id -> "'"+id.toLowerCase(Locale.ROOT)+"'").collect(Collectors.joining(",")); }
  static String detailsSql(String entityIdsSql) { return "JSON_MERGE_PATCH(COALESCE((SELECT JSON_OBJECTAGG(LOWER(d.stat_key),d.stat_value) FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='custom'),JSON_OBJECT()),JSON_OBJECT("+
      "'blocks_mined',COALESCE((SELECT JSON_OBJECTAGG(LOWER(d.stat_key),d.stat_value) FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='blocks_mined'),JSON_OBJECT()),"+
      "'kills_entity',COALESCE((SELECT JSON_OBJECTAGG(LOWER(d.stat_key),d.stat_value) FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='kills_entity'),JSON_OBJECT()),"+
      "'distance_cm',COALESCE((SELECT JSON_OBJECTAGG(LOWER(d.stat_key),d.stat_value) FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='distance_cm'),JSON_OBJECT()),"+
      "'deaths_entity',COALESCE((SELECT JSON_OBJECTAGG(LOWER(d.stat_key),d.stat_value) FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='deaths_entity'),JSON_OBJECT()),"+
      "'deaths_by',COALESCE((SELECT JSON_OBJECTAGG(x.cause,x.n) FROM (SELECT uuid,LOWER(cause_type) cause,COUNT(*) n FROM player_deaths WHERE entity_type IS NULL AND LOWER(cause_type) NOT IN ("+entityIdsSql+") GROUP BY uuid,LOWER(cause_type)) x WHERE x.uuid=p.uuid AND NOT EXISTS (SELECT 1 FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='deaths_entity' AND LOWER(d.stat_key)=x.cause)),JSON_OBJECT()),"+
      "'blocks_placed',COALESCE((SELECT JSON_OBJECTAGG(LOWER(d.stat_key),d.stat_value) FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='blocks_placed'),JSON_OBJECT()),"+
      "'crafted',COALESCE((SELECT JSON_OBJECTAGG(x.stat_key,x.n) FROM (SELECT uuid,LOWER(stat_key) stat_key,MAX(stat_value) n FROM player_stats_details WHERE category IN ('crafted','craft_item') GROUP BY uuid,LOWER(stat_key)) x WHERE x.uuid=p.uuid),JSON_OBJECT())))"; }
  static String playerStatsExtSql(String entityIdsSql) { return "SELECT p.uuid,p.mc_name,COALESCE(p.money,0) AS money,COALESCE(p.playtime_s,0) AS playtime_s,COALESCE(p.kills,0) AS kills,COALESCE(p.deaths,0) AS deaths,COALESCE(p.blocks_mined,0) AS blocks_mined,p.updated_at,COALESCE((SELECT SUM(d.stat_value)/100.0 FROM player_stats_details d WHERE d.uuid=p.uuid AND d.category='distance_cm'),0) AS distance_m,CAST(COALESCE(p.kills,0)/GREATEST(COALESCE(p.deaths,0),1) AS DECIMAL(20,4)) AS kd,COALESCE((SELECT SUM(w.playtime_s) FROM player_playtime_world w WHERE w.uuid=p.uuid),0) AS playtime,"+detailsSql(entityIdsSql)+" AS details FROM v_player_stats p"; }
  static void migrate(Connection c) throws SQLException {
    migrate(c,entityIdsSql(Arrays.stream(EntityType.values()).map(EntityType::name)));
  }
  static void migrate(Connection c,String entityIdsSql) throws SQLException {
    DeathEvents.migrate(c);
    try (Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS market_events (id BIGINT AUTO_INCREMENT PRIMARY KEY,item VARCHAR(64) NOT NULL,qty INT NOT NULL,unit_price DECIMAL(20,4) NOT NULL,total_cp DECIMAL(20,2) NOT NULL,source VARCHAR(8) NOT NULL,ts BIGINT NOT NULL,INDEX market_event_time(ts,item))");
      s.execute("CREATE TABLE IF NOT EXISTS market_price_history (item VARCHAR(64) NOT NULL,hour_ts BIGINT NOT NULL,avg_price DECIMAL(20,4) NOT NULL,min_price DECIMAL(20,4) NOT NULL,max_price DECIMAL(20,4) NOT NULL,volume_qty BIGINT NOT NULL,PRIMARY KEY(item,hour_ts))");
      s.execute("CREATE TABLE IF NOT EXISTS market_catalog (item VARCHAR(64) PRIMARY KEY,buy_price DECIMAL(20,2) NOT NULL,sell_price DECIMAL(20,2) NOT NULL,base_price DECIMAL(20,4) NOT NULL,updated_at BIGINT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS leaderboard_snapshots (week_ts BIGINT NOT NULL,day_key DATE NOT NULL,board VARCHAR(20) NOT NULL,uuid CHAR(36) NOT NULL,value DECIMAL(24,4) NOT NULL,PRIMARY KEY(week_ts,day_key,board,uuid))");
      s.execute("CREATE OR REPLACE VIEW v_player_stats_detail AS SELECT uuid,category,stat_key AS `key`,stat_value AS value FROM player_stats_details");
      s.execute("CREATE OR REPLACE VIEW v_player_stats_ext AS "+playerStatsExtSql(entityIdsSql));
      s.execute("CREATE OR REPLACE VIEW v_leaderboard_values AS "+LEADERBOARD_VALUES_SQL);
      s.execute("CREATE OR REPLACE VIEW v_leaderboards AS "+LEADERBOARDS_SQL);
      PriceMeta.ensureSchema(s);
      s.execute("CREATE OR REPLACE VIEW v_market AS SELECT c.item,CASE WHEN COALESCE(l.lock_buy,0)=1 THEN NULL ELSE c.buy_price END buy_price,c.sell_price sell_price,c.base_price,COALESCE(e.qty,0) volume_24h_qty,COALESCE(e.cp,0) volume_24h_cp,COALESCE(e.trades,0) trades_24h,"+CHANGE_24H_SQL+" change_24h_pct,c.updated_at,COALESCE(l.lock_buy,0)=0 buy_enabled,TRUE sell_enabled FROM market_catalog c LEFT JOIN (SELECT item,SUM(qty) qty,SUM(total_cp) cp,COUNT(*) trades FROM market_events WHERE ts>=UNIX_TIMESTAMP()-86400 GROUP BY item) e ON e.item=c.item LEFT JOIN market_price_history h ON h.item=c.item AND h.hour_ts=(SELECT MAX(h2.hour_ts) FROM market_price_history h2 WHERE h2.item=c.item AND h2.hour_ts<=UNIX_TIMESTAMP()-86400) LEFT JOIN market_locks l ON l.item=c.item LEFT JOIN price_meta pm ON pm.id=1");
      s.execute("CREATE OR REPLACE VIEW v_market_history AS SELECT item,hour_ts,avg_price,min_price,max_price,volume_qty FROM market_price_history WHERE hour_ts>=UNIX_TIMESTAMP()-90*86400");
      MarketEffective.ensureSchema(s);
    }
  }
  static void maintain(Connection c, Instant now) throws SQLException {
    ZonedDateTime berlin=now.atZone(ZoneId.of("Europe/Berlin"));
    long week=berlin.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneId.of("Europe/Berlin")).toEpochSecond();
    try (PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO leaderboard_snapshots(week_ts,day_key,board,uuid,value) SELECT ?,?,board,uuid,value FROM v_leaderboard_values")) {q.setLong(1,week);q.setDate(2,Date.valueOf(berlin.toLocalDate()));q.executeUpdate();}
    try (PreparedStatement q=c.prepareStatement("INSERT INTO market_price_history(item,hour_ts,avg_price,min_price,max_price,volume_qty) SELECT item,FLOOR(ts/3600)*3600,SUM(total_cp)/SUM(qty),MIN(unit_price),MAX(unit_price),SUM(qty) FROM market_events WHERE ts>=? GROUP BY item,FLOOR(ts/3600) ON DUPLICATE KEY UPDATE avg_price=VALUES(avg_price),min_price=VALUES(min_price),max_price=VALUES(max_price),volume_qty=VALUES(volume_qty)")) {q.setLong(1,now.getEpochSecond()-90L*86400);q.executeUpdate();}
  }
  static void start(CraftCore core) throws Exception {
    core.tx(c->{migrate(c);return null;});
    Bukkit.getScheduler().runTaskTimerAsynchronously(core,()->{try {core.tx(c->{maintain(c,Instant.now());return null;});} catch(Exception e){core.getLogger().warning("Website-Statistik: "+e.getMessage());}},20L,72000L);
  }
  static final String SNAPSHOT_SQL = "INSERT INTO market_price_history(item,hour_ts,avg_price,min_price,max_price,volume_qty) SELECT item,?,sell_price,sell_price,sell_price,0 FROM market_catalog WHERE 1=1 ON DUPLICATE KEY UPDATE avg_price=CASE WHEN volume_qty=0 THEN VALUES(avg_price) ELSE avg_price END,min_price=CASE WHEN volume_qty=0 THEN VALUES(min_price) ELSE min_price END,max_price=CASE WHEN volume_qty=0 THEN VALUES(max_price) ELSE max_price END";
  static void snapshot(Connection c, Instant now) throws SQLException {
    try(PreparedStatement q=c.prepareStatement(SNAPSHOT_SQL)) { q.setLong(1,Math.floorDiv(now.getEpochSecond(),3600)*3600); q.executeUpdate(); }
  }
  static void catalog(Connection c,String item,long buy,long sell,double base,long now) throws SQLException {
    try(PreparedStatement q=c.prepareStatement("INSERT INTO market_catalog(item,buy_price,sell_price,base_price,updated_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE buy_price=VALUES(buy_price),sell_price=VALUES(sell_price),base_price=VALUES(base_price),updated_at=VALUES(updated_at)")) {
      q.setString(1,item); q.setDouble(2,buy/100.0); q.setDouble(3,sell/100.0); q.setDouble(4,base); q.setLong(5,now/1000); q.executeUpdate();
    }
  }
  static void event(Connection c, String item, int qty, long cents, String source) throws SQLException {
    try (PreparedStatement q=c.prepareStatement("INSERT INTO market_events(item,qty,unit_price,total_cp,source,ts) VALUES(?,?,?,?,?,?)")) {
      q.setString(1,item); q.setInt(2,qty); q.setDouble(3,cents / 100.0 / qty); q.setDouble(4,cents / 100.0); q.setString(5,source); q.setLong(6,System.currentTimeMillis()/1000); q.executeUpdate();
    }
  }
}
