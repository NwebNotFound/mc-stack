package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeathEventsTest {
  @Test void backfillAndLiveEventsAreDeduplicatedAndTotalAllDeaths() throws Exception {
    try(Connection c=DriverManager.getConnection("jdbc:h2:mem:deaths;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
      DeathEvents.migrate(c); DeathEvents.migrate(c);
      var death=new DeathEvents.Death(UUID.randomUUID().toString(),"player",100,"FALL",null,null,null,"survival");
      DeathEvents.insert(c,death); DeathEvents.insert(c,death);
      assertTrue(DeathEvents.backfillOne(c,"player","survival",200,3,Map.of("zombie",1L)));
      assertFalse(DeathEvents.backfillOne(c,"player","survival",200,3,Map.of("zombie",1L)));
      Map<String,Long> totals=new HashMap<>(); try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT `key`,`value` FROM deaths_by WHERE uuid='player'")){while(r.next())totals.put(r.getString(1),r.getLong(2));}
      assertEquals(Map.of("fall",1L,"zombie",1L,"unknown",1L),totals);
      assertEquals(3,totals.values().stream().mapToLong(Long::longValue).sum());
    }
  }
  @Test void backfillUsesPlayerStatsDirectory() {
    assertEquals(new java.io.File("world/players/stats"), DeathEvents.statsDirectory(new java.io.File("world")));
  }
  @Test void recordedEntityDoesNotDuplicateVanilla() { assertEquals(Map.of("unknown",1L),DeathEvents.baseline(2,Map.of("zombie",1L),Map.of("zombie",1L))); }
}
