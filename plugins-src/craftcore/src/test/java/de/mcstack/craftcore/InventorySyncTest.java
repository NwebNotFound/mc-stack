package de.mcstack.craftcore;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventorySyncTest {
  @Test void summaryPrefersDisplayNameThenEnchantsAndClips() {
    assertEquals("Schwert", InventorySync.metaSummary("  Schwert ", List.of("Sharpness V")));
    assertEquals("Sharpness V", InventorySync.metaSummary("  ", List.of("Sharpness V")));
    assertEquals("", InventorySync.metaSummary(null, List.of()));
    assertEquals("Sharpness V", InventorySync.enchantSummary("minecraft:sharpness", 5));
    assertEquals("Fire aspect II", InventorySync.enchantSummary("fire_aspect", 2));
    assertEquals(List.of("Fire aspect II", "Sharpness V"), InventorySync.enchantLines(Map.of("sharpness", 5, "fire_aspect", 2)));
    assertEquals(80, InventorySync.metaSummary("x".repeat(90), List.of()).length());
  }
  @Test void periodIsFiveMinutesOnLobbyAndSurvival() {
    assertEquals(6000L, InventorySync.PERIOD_TICKS);
    assertTrue(InventorySync.tracks("Lobby"));
    assertTrue(InventorySync.tracks("survival"));
    assertFalse(InventorySync.tracks("vip"));
  }
  @Test void replacePublishesSurvivalAndClearsRemovedSlots() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:inv_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
      InventorySync.migrate(c); InventorySync.migrate(c);
      Timestamp ts = Timestamp.valueOf("2026-10-30 10:15:00");
      String uuid = "00000000-0000-0000-0000-000000000010";
      InventorySync.replace(c, uuid, "survival", List.of(new InventorySync.Slot(0, "DIAMOND_SWORD", 1, "Sharpness V"), new InventorySync.Slot(1, "STONE", 64, "")), ts);
      InventorySync.replace(c, uuid, "lobby", List.of(new InventorySync.Slot(8, "COMPASS", 1, "Menue")), ts);
      InventorySync.replace(c, uuid, "survival", List.of(new InventorySync.Slot(0, "DIAMOND_SWORD", 1, "Sharpness V"), new InventorySync.Slot(-1, "DIRT", 1, ""), new InventorySync.Slot(2, "AIR", 0, "")), ts);
      try (ResultSet r = c.createStatement().executeQuery("SELECT uuid, slot, material, amount, meta_summary, updated_at FROM v_player_inventory ORDER BY slot")) {
        assertColumns(r.getMetaData());
        assertTrue(r.next());
        assertEquals(uuid, r.getString("uuid")); assertEquals(0, r.getInt("slot")); assertEquals("DIAMOND_SWORD", r.getString("material"));
        assertEquals(1, r.getInt("amount")); assertEquals("Sharpness V", r.getString("meta_summary")); assertNotNull(r.getTimestamp("updated_at"));
        assertFalse(r.next());
      }
      InventorySync.replace(c, uuid, "survival", List.of(), ts);
      try (ResultSet r = c.createStatement().executeQuery("SELECT COUNT(*) FROM v_player_inventory")) { r.next(); assertEquals(0, r.getInt(1)); }
      String lobbyOnly = "00000000-0000-0000-0000-000000000011";
      InventorySync.replace(c, lobbyOnly, "lobby", List.of(new InventorySync.Slot(8, "COMPASS", 1, "")), ts);
      try (ResultSet r = c.createStatement().executeQuery("SELECT material FROM v_player_inventory WHERE uuid='"+lobbyOnly+"'")) { assertTrue(r.next()); assertEquals("COMPASS", r.getString(1)); }
      c.setAutoCommit(false);
      InventorySync.replace(c, lobbyOnly, "lobby", List.of(new InventorySync.Slot(1, "DIRT", 3, "")), ts);
      c.rollback();
      try (ResultSet r = c.createStatement().executeQuery("SELECT material FROM player_inventory WHERE uuid='"+lobbyOnly+"'")) { assertTrue(r.next()); assertEquals("COMPASS", r.getString(1)); assertFalse(r.next()); }
    }
  }
  @Test void failingViewGrantDoesNotBreakStartup() throws Exception {
    for (String view : List.of("v_player_inventory", "v_player_balance", "v_order_fulfillment")) {
      assertEquals("GRANT SELECT ON craftcore."+view+" TO 'creepy_ro'@'%'", ReadGrants.sql(view));
    }
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:viewgrant_"+UUID.randomUUID()+";MODE=MySQL")) {
      assertFalse(ReadGrants.apply(c, "v_player_inventory"));
      assertFalse(ReadGrants.apply(c, "v_player_balance"));
      assertFalse(ReadGrants.apply(c, "v_order_fulfillment"));
      try (ResultSet r = c.createStatement().executeQuery("SELECT 1")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
    }
  }
  @Test void grantScriptMatchesReadGrant() throws Exception {
    Path script = Path.of("scripts/v-website-reads.sql");
    if (!Files.exists(script)) script = Path.of("../../scripts/v-website-reads.sql");
    String text = Files.readString(script);
    for (String view : List.of("v_player_inventory", "v_player_balance", "v_order_fulfillment")) {
      assertEquals("GRANT SELECT ON craftcore."+view+" TO 'creepy_ro'@'%'", ReadGrants.sql(view));
      assertTrue(text.contains(ReadGrants.sql(view)));
    }
  }
  private static void assertColumns(ResultSetMetaData meta) throws SQLException {
    List<String> names = new ArrayList<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) names.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
    assertEquals(List.of("uuid", "slot", "material", "amount", "meta_summary", "updated_at"), names);
  }
}
