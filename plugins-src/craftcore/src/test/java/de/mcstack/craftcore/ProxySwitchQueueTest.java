package de.mcstack.craftcore;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProxySwitchQueueTest {
  @Test void onlyLatestChoiceRemainsAndQuitCannotCancelLaterLogin() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:switches;MODE=MySQL")) {
      c.setAutoCommit(false);
      ProxySwitchQueue.migrate(c);
      UUID player = UUID.randomUUID();
      ProxySwitchQueue.enqueue(c, player, "survival", "lobby", 1000);
      ProxySwitchQueue.enqueue(c, player, "vipworld-1", "lobby", 2000);
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT server FROM proxy_switch_requests ORDER BY id DESC LIMIT 1")) {
        assertTrue(r.next()); assertEquals("vipworld-1", r.getString(1)); assertFalse(r.next());
      }
      ProxySwitchQueue.cancel(c, player, 1500);
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM proxy_switch_requests")) {
        r.next(); assertEquals(2, r.getInt(1));
      }
      ProxySwitchQueue.cancel(c, player, 2500);
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM proxy_switch_requests")) {
        r.next(); assertEquals(3, r.getInt(1));
      }
      c.commit();
    }
  }
}
