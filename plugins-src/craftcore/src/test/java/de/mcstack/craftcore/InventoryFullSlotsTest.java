package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Website INGEST.md: full inventory, slots 0-40 (hotbar, main, armor 36-39, offhand 40), each with slot. */
class InventoryFullSlotsTest {
  @Test void viewKeepsSlotColumnAndAllFortyOneSlots() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:invfull_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
      InventorySync.migrate(c);
      String uuid = "00000000-0000-0000-0000-0000000c0de1";
      List<InventorySync.Slot> all = new ArrayList<>();
      for (int s = 0; s <= 40; s++) all.add(new InventorySync.Slot(s, s == 40 ? "SHIELD" : s == 38 ? "ELYTRA" : "STONE", 1, ""));
      InventorySync.replace(c, uuid, "survival", all, Timestamp.valueOf("2026-10-30 11:00:00"));
      try (ResultSet r = c.createStatement().executeQuery("SELECT * FROM v_player_inventory WHERE uuid='"+uuid+"' ORDER BY slot")) {
        ResultSetMetaData md = r.getMetaData(); boolean hasSlot = false;
        for (int i = 1; i <= md.getColumnCount(); i++) if (md.getColumnLabel(i).equalsIgnoreCase("slot")) hasSlot = true;
        assertTrue(hasSlot, "v_player_inventory must keep column slot");
        int n = 0;
        while (r.next()) {
          assertEquals(n, r.getInt("slot"));
          if (n == 38) assertEquals("ELYTRA", r.getString("material"));
          if (n == 40) assertEquals("SHIELD", r.getString("material"));
          n++;
        }
        assertEquals(41, n);
        assertTrue(n <= 64, "bot ingest limit (INV_MAX=64)");
      }
    }
  }
}