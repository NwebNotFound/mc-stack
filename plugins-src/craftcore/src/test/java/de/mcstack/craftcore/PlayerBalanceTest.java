package de.mcstack.craftcore;

import java.math.BigDecimal;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerBalanceTest {
  @Test void viewExposesAccountBalanceInCp() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:bal_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
      try (Statement s = c.createStatement()) {
        s.execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL)");
        s.execute("CREATE TABLE known_players(uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL)");
      }
      PlayerBalance.migrate(c); PlayerBalance.migrate(c);
      try (Statement s = c.createStatement()) {
        s.execute("INSERT INTO accounts(uuid,balance) VALUES('p1',1),('p2',199),('p3',0)");
        s.execute("INSERT INTO known_players VALUES('p1','Ada'),('p2','Bea')");
      }
      try (ResultSet r = c.createStatement().executeQuery("SELECT uuid, name, balance_cp, updated_at FROM v_player_balance ORDER BY uuid")) {
        assertTrue(r.next()); assertEquals("p1", r.getString("uuid")); assertEquals("Ada", r.getString("name"));
        assertEquals(0, new BigDecimal("0.01").compareTo(r.getBigDecimal("balance_cp"))); assertNotNull(r.getTimestamp("updated_at"));
        assertTrue(r.next()); assertEquals("p2", r.getString("uuid")); assertEquals("Bea", r.getString("name"));
        assertEquals(0, new BigDecimal("1.99").compareTo(r.getBigDecimal("balance_cp")));
        assertTrue(r.next()); assertEquals("p3", r.getString("uuid")); assertNull(r.getString("name"));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getBigDecimal("balance_cp")));
        assertFalse(r.next());
      }
    }
  }
  @Test void mariaDbRefreshesTimestampOnEveryAccountUpdate() {
    assertTrue(PlayerBalance.timestampDdl("MariaDB").contains("ON UPDATE CURRENT_TIMESTAMP"));
    assertFalse(PlayerBalance.timestampDdl("H2").contains("ON UPDATE"));
  }
}
