package de.mcstack.craftcore;

import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class WebsiteStatsMariaDbTest {
  @Test void viewsSnapshotsAndHistory() throws Exception {
    String password=System.getenv("CRAFTCORE_DB_PASSWORD");
    assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
    try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE known_players(uuid CHAR(36) PRIMARY KEY,name VARCHAR(16))");
      s.execute("CREATE TABLE accounts(uuid CHAR(36) PRIMARY KEY,balance BIGINT)");
      s.execute("CREATE TABLE market_locks(item VARCHAR(64) PRIMARY KEY,lock_buy BOOLEAN NOT NULL DEFAULT FALSE,lock_sell BOOLEAN NOT NULL DEFAULT FALSE,reason VARCHAR(128),updated_at BIGINT NOT NULL DEFAULT 0)");
      PlayerStats.migrate(c);
      s.execute("CREATE TABLE teams(id BIGINT PRIMARY KEY,name VARCHAR(16),color VARCHAR(4))");
      s.execute("CREATE TABLE members(uuid CHAR(36) PRIMARY KEY,team_id BIGINT NOT NULL,role VARCHAR(8))");
      Presence.migrate(c); Presence.migrate(c);
      s.execute("CREATE TABLE presence_boolean (value BOOLEAN NOT NULL PRIMARY KEY)");
      s.execute("INSERT INTO presence_boolean VALUES (FALSE),(TRUE)");
      String script=java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/v-player-stats.sql"));
      String view=script.substring(script.indexOf("CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_player_stats AS"),script.indexOf("GRANT SELECT ON craftcore.v_player_stats"));
      s.execute(view.trim());
      WebsiteStats.migrate(c);
      WebsiteStats.migrate(c);
      s.execute("INSERT INTO market_catalog VALUES('STONE',1.50,0.50,1.00,1)");
      s.execute("INSERT INTO market_locks(item,lock_buy,lock_sell) VALUES('STONE',TRUE,FALSE)");
      s.execute("INSERT INTO market_effective VALUES('STONE',1,1,1)");
      s.execute("INSERT INTO market_catalog VALUES('DIRT',0.20,0.10,0.10,1)");
      s.execute("INSERT INTO market_effective VALUES('DIRT',0,1,1)");
      try(ResultSet r=s.executeQuery("SELECT buy_price,sell_price,buy_enabled,sell_enabled FROM v_market WHERE item='DIRT'")){assertTrue(r.next());assertNull(r.getObject(1));assertEquals(0.1,r.getDouble(2),0.001);assertFalse(r.getBoolean(3));assertTrue(r.getBoolean(4));}
      s.execute("INSERT INTO market_catalog VALUES('SAND',0.20,0.10,0.10,1)");
      try(ResultSet r=s.executeQuery("SELECT buy_price,sell_price,buy_enabled,sell_enabled FROM v_market WHERE item='SAND'")){assertTrue(r.next());assertNull(r.getObject(1));assertNull(r.getObject(2));assertFalse(r.getBoolean(3));assertFalse(r.getBoolean(4));}
      try(ResultSet r=s.executeQuery("SELECT buy_price,sell_price,buy_enabled,sell_enabled FROM v_market WHERE item='STONE'")){assertTrue(r.next());assertNull(r.getObject(1));assertEquals(0.5,r.getDouble(2),0.001);assertFalse(r.getBoolean(3));assertTrue(r.getBoolean(4));}
      s.execute("INSERT INTO known_players VALUES('p','Player')");
      s.execute("INSERT INTO accounts VALUES('p',1000)");
      try(ResultSet r=s.executeQuery("SELECT team,team_color,last_seen,online FROM v_player_stats WHERE uuid='p'")) { assertTrue(r.next()); assertNull(r.getString(1)); assertNull(r.getString(2)); assertNull(r.getString(3)); assertEquals(0,r.getInt(4)); }
      s.execute("INSERT INTO teams VALUES(1,'Builders','&9')");
      s.execute("INSERT INTO members VALUES('p',1,'owner')");
      java.util.UUID id=java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
      s.execute("UPDATE known_players SET uuid='"+id+"' WHERE uuid='p'");
      s.execute("UPDATE accounts SET uuid='"+id+"' WHERE uuid='p'");
      s.execute("UPDATE members SET uuid='"+id+"' WHERE uuid='p'");
      s.execute("SET SESSION time_zone='+01:00'");
      try(ResultSet r=s.executeQuery("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='player_presence' AND COLUMN_NAME='last_seen'")) { assertTrue(r.next()); assertEquals("datetime",r.getString(1)); }
      Instant before;
      try(ResultSet r=s.executeQuery("SELECT DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-%dT%H:%i:%sZ')")) { assertTrue(r.next()); before=Instant.parse(r.getString(1)); }
      Presence.record(c,id,"lobby",true); Presence.record(c,id,"pvp",true);
      Presence.resetBackend(c,"lobby");
      try(ResultSet r=s.executeQuery("SELECT online FROM v_player_stats WHERE uuid='"+id+"'")) { assertTrue(r.next()); assertTrue(r.getBoolean(1)); }
      Presence.record(c,id,"lobby",false);
      Instant after;
      try(ResultSet r=s.executeQuery("SELECT DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-%dT%H:%i:%sZ')")) { assertTrue(r.next()); after=Instant.parse(r.getString(1)); }
      try(ResultSet r=s.executeQuery("SELECT team,team_color,last_seen,online FROM v_player_stats WHERE uuid='"+id+"'")) { assertTrue(r.next()); assertEquals("Builders",r.getString(1)); assertEquals("&9",r.getString(2)); String seen=r.getString(3); assertTrue(seen.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")); Instant written=Instant.parse(seen); assertFalse(written.isBefore(before)); assertFalse(written.isAfter(after)); assertEquals(1,r.getInt(4)); }
      try(ResultSet r=s.executeQuery("SELECT v.last_seen,DATE_FORMAT(MAX(p.last_seen),'%Y-%m-%dT%H:%i:%sZ') FROM v_player_stats v JOIN player_presence p ON p.uuid=v.uuid WHERE v.uuid='"+id+"' GROUP BY v.last_seen")) { assertTrue(r.next()); assertEquals(r.getString(2),r.getString(1)); }
      // A known UTC DATETIME must survive the view without session-zone conversion.
      Instant expected=Instant.parse("2026-01-15T12:34:56Z");
      s.execute("UPDATE player_presence SET last_seen='2026-01-15 12:34:56' WHERE uuid='"+id+"'");
      try(ResultSet r=s.executeQuery("SELECT last_seen FROM v_player_stats WHERE uuid='"+id+"'")) { assertTrue(r.next()); String seen=r.getString(1); assertTrue(seen.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")); assertEquals(expected,Instant.parse(seen)); }
      try(ResultSet r=s.executeQuery("SELECT DATA_TYPE,COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='v_player_stats' AND COLUMN_NAME='online'")) { assertTrue(r.next()); assertEquals("tinyint",r.getString(1)); assertEquals("tinyint(1)",r.getString(2)); }
      Presence.resetBackend(c,"pvp");
      try(ResultSet r=s.executeQuery("SELECT online FROM v_player_stats WHERE uuid='"+id+"'")) { assertTrue(r.next()); assertFalse(r.getBoolean(1)); }
      s.execute("UPDATE known_players SET uuid='p' WHERE uuid='"+id+"'");
      s.execute("UPDATE accounts SET uuid='p' WHERE uuid='"+id+"'");
      s.execute("INSERT INTO player_stats VALUES('p',100,4,2,8,CURRENT_TIMESTAMP)");
      s.execute("INSERT INTO player_stats_details VALUES('p','distance_cm','WALK_ONE_CM',250)");
      s.execute("INSERT INTO player_stats_details VALUES('p','deaths_entity','ZOMBIE',2),('p','craft_item','STONE',3),('p','crafted','STONE',3),('p','blocks_placed','DIRT',4),('p','custom','JUMP',123)");
      DeathEvents.insert(c,new DeathEvents.Death("event-fall","p",1,"FALL",null,null,null,"survival"));
      DeathEvents.insert(c,new DeathEvents.Death("event-mob","p",2,"ENTITY_ATTACK","zombie","ZOMBIE",null,"survival"));
      DeathEvents.insert(c,new DeathEvents.Death("event-collision","p",3,"ZOMBIE",null,null,null,"survival"));
      try(ResultSet r=s.executeQuery("SELECT details FROM v_player_stats_ext WHERE uuid='p'")) {
        assertTrue(r.next());
        var json=com.google.gson.JsonParser.parseString(r.getString(1)).getAsJsonObject();
        assertEquals(java.util.Set.of("distance_cm","deaths_entity","deaths_by","blocks_placed","crafted","jump"),json.keySet());
        assertEquals(123,json.get("jump").getAsInt());
        assertEquals(250,json.getAsJsonObject("distance_cm").get("walk_one_cm").getAsInt());
        assertEquals(2,json.getAsJsonObject("deaths_entity").get("zombie").getAsInt());
        assertEquals(java.util.Set.of("fall"),json.getAsJsonObject("deaths_by").keySet());
        assertTrue(java.util.Collections.disjoint(json.getAsJsonObject("deaths_entity").keySet(),json.getAsJsonObject("deaths_by").keySet()));
        assertEquals(4,json.getAsJsonObject("blocks_placed").get("dirt").getAsInt());
        assertEquals(3,json.getAsJsonObject("crafted").get("stone").getAsInt());
      }
      s.execute("INSERT INTO player_playtime_world VALUES('p','survival',100,CURRENT_TIMESTAMP)");
      Instant time=Instant.parse("2026-10-26T12:00:00Z");
      WebsiteStats.maintain(c,time);
      s.execute("UPDATE accounts SET balance=1500 WHERE uuid='p'");
      try(ResultSet r=s.executeQuery("SELECT value FROM v_leaderboards WHERE board='money' AND period='week'")){assertTrue(r.next());assertEquals(5.0,r.getDouble(1),0.001);}
      try(ResultSet r=s.executeQuery("SELECT distance_m,kd,playtime,JSON_VALID(details) FROM v_player_stats_ext")){assertTrue(r.next());assertEquals(2.5,r.getDouble(1),0.001);assertEquals(2,r.getDouble(2),0.001);assertEquals(100,r.getLong(3));assertEquals(1,r.getInt(4));}
      c.setAutoCommit(false); WebsiteStats.event(c,"STONE",2,300,"shop"); c.rollback();
      try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM market_events")){r.next();assertEquals(0,r.getInt(1));}
      WebsiteStats.event(c,"STONE",2,300,"shop");c.commit();
      WebsiteStats.maintain(c,Instant.now());
      try(ResultSet r=s.executeQuery("SELECT avg_price,volume_qty FROM v_market_history WHERE item='STONE'")){assertTrue(r.next());assertEquals(1.5,r.getDouble(1),0.001);assertEquals(2,r.getInt(2));}
    }
  }
}
