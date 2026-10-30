package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseViewGrantTest {
  @Test void failingGrantDoesNotBreakStartup() throws Exception {
    assertEquals("GRANT SELECT ON craftcore.v_rcon_purchase_result TO 'creepy_ro'@'%'", PurchaseViewGrant.SQL);
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:grant;MODE=MySQL")) {
      assertFalse(PurchaseViewGrant.apply(c));
      assertFalse(PurchaseViewGrant.applyBank(c));
      assertEquals("GRANT SELECT ON craftcore.bank_buy_orders TO 'creepy_ro'@'%'", PurchaseViewGrant.BANK_SQL);
      try (ResultSet r = c.createStatement().executeQuery("SELECT 1")) {
        assertTrue(r.next());
        assertEquals(1, r.getInt(1));
      }
    }
  }
}
