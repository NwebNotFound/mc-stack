package de.mcstack.craftcore;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriceMetaTest {
  static Connection h2() throws SQLException { return DriverManager.getConnection("jdbc:h2:mem:pm_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"); }
  @Test void parsesIsoWithZoneAndRejectsGarbage() {
    var cfg = new YamlConfiguration();
    cfg.set("effective_from", "2026-11-01T05:00:00+01:00");
    assertEquals(Instant.parse("2026-11-01T04:00:00Z"), PriceMeta.effectiveFrom(cfg));
    cfg.set("effective_from", "morgen");
    assertThrows(IllegalArgumentException.class, () -> PriceMeta.effectiveFrom(cfg));
    assertEquals(PriceMeta.PLACEHOLDER, PriceMeta.effectiveFrom(new YamlConfiguration()));
  }
  /** Platzhalter oder kaputter Wert blockiert den Markt nicht: kein Fehler, View leer, change_24h normal. */
  @Test void placeholderOrInvalidValueNeverBlocksMarket() throws Exception {
    var cfg = new YamlConfiguration();
    cfg.set("effective_from", "1970-01-01T00:00:00Z");
    assertEquals(PriceMeta.PLACEHOLDER, PriceMeta.effectiveFromOrPlaceholder(cfg, null));
    cfg.set("effective_from", "morgen");
    assertEquals(PriceMeta.PLACEHOLDER, assertDoesNotThrow(() -> PriceMeta.effectiveFromOrPlaceholder(cfg, java.util.logging.Logger.getLogger("t"))));
    try (Connection c = h2(); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE market_catalog (item VARCHAR(64) PRIMARY KEY,sell_price DECIMAL(20,2))");
      s.execute("CREATE TABLE market_price_history (item VARCHAR(64),hour_ts BIGINT,avg_price DECIMAL(20,4))");
      s.execute("INSERT INTO market_catalog VALUES('STONE',1.10)");
      s.execute("INSERT INTO market_price_history SELECT 'STONE',FLOOR((UNIX_TIMESTAMP()-86400)/3600)*3600,1.00");
      assertDoesNotThrow(() -> PriceMeta.store(c, PriceMeta.PLACEHOLDER));
      assertDoesNotThrow(() -> PriceMeta.store(c, PriceMeta.PLACEHOLDER));
      try (ResultSet r = s.executeQuery("SELECT " + WebsiteStats.CHANGE_24H_SQL + " FROM market_catalog c LEFT JOIN market_price_history h ON h.item=c.item AND h.hour_ts=FLOOR((UNIX_TIMESTAMP()-86400)/3600)*3600 LEFT JOIN price_meta pm ON pm.id=1")) {
        r.next(); assertEquals(10.0, r.getDouble(1), 1e-6);
      }
    }
  }
  /** Jar-Wert: kein 1970-Platzhalter, gueltiges ISO 8601 mit Zone. */
  @Test void jarEffectiveFromIsRealIsoWithZone() {
    String raw = RecipeArbitrageTest.prices().getString("effective_from");
    assertNotNull(raw);
    var odt = assertDoesNotThrow(() -> java.time.OffsetDateTime.parse(raw.trim()));
    assertTrue(raw.trim().matches(".*(Z|[+-]\\d{2}:\\d{2})$"), "Zone fehlt: " + raw);
    assertNotEquals(PriceMeta.PLACEHOLDER, odt.toInstant());
    assertTrue(odt.getYear() > 1970);
    assertEquals(odt.toInstant(), PriceMeta.effectiveFromOrPlaceholder(RecipeArbitrageTest.prices(), null));
    assertEquals(java.time.Instant.parse("2026-10-30T13:05:00Z"), odt.toInstant());
  }
  @Test void viewExposesUtcDatetimeAndPlaceholderMeansEmpty() throws Exception {
    try (Connection c = h2(); Statement s = c.createStatement()) {
      PriceMeta.store(c, PriceMeta.PLACEHOLDER);
      try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM v_price_meta")) { r.next(); assertEquals(0, r.getInt(1)); }
      PriceMeta.store(c, Instant.parse("2026-11-01T04:00:00Z"));
      try (ResultSet r = s.executeQuery("SELECT effective_from FROM v_price_meta")) {
        assertTrue(r.next()); assertEquals("2026-11-01T04:00", r.getObject(1, java.time.LocalDateTime.class).toString()); assertFalse(r.next());
      }
    }
  }
  /** change_24h_pct nutzt nur Kurse ab effective_from als Basis; liegt die Vergleichsstunde davor, ist der Wert NULL (neu). */
  @Test void change24hIsNullUntilBaselineAfterEffectiveFrom() throws Exception {
    try (Connection c = h2(); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE market_catalog (item VARCHAR(64) PRIMARY KEY,sell_price DECIMAL(20,2))");
      s.execute("CREATE TABLE market_price_history (item VARCHAR(64),hour_ts BIGINT,avg_price DECIMAL(20,4))");
      PriceMeta.ensureSchema(s);
      s.execute("INSERT INTO market_catalog VALUES('STONE',1.10)");
      s.execute("INSERT INTO market_price_history SELECT 'STONE',FLOOR((UNIX_TIMESTAMP()-86400)/3600)*3600,1.00");
      String q = "SELECT " + WebsiteStats.CHANGE_24H_SQL + " FROM market_catalog c LEFT JOIN market_price_history h ON h.item=c.item AND h.hour_ts=FLOOR((UNIX_TIMESTAMP()-86400)/3600)*3600 LEFT JOIN price_meta pm ON pm.id=1";
      try (ResultSet r = s.executeQuery(q)) { r.next(); assertEquals(10.0, r.getDouble(1), 1e-6); }
      PriceMeta.store(c, Instant.now().minusSeconds(3600));
      try (ResultSet r = s.executeQuery(q)) { r.next(); assertNull(r.getObject(1)); }
      PriceMeta.store(c, Instant.now().minusSeconds(3 * 86400));
      try (ResultSet r = s.executeQuery(q)) { r.next(); assertEquals(10.0, r.getDouble(1), 1e-6); }
    }
  }
}
