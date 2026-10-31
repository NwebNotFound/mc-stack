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
      s.execute("CREATE VIEW v_player_stats AS SELECT p.uuid,p.name mc_name,a.balance/100.0 money,t.playtime_s,t.kills,t.deaths,t.blocks_mined,t.updated_at FROM known_players p LEFT JOIN accounts a ON a.uuid=p.uuid LEFT JOIN player_stats t ON t.uuid=p.uuid");
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
