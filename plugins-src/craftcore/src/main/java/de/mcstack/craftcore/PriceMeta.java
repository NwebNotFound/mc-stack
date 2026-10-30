package de.mcstack.craftcore;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import org.bukkit.configuration.Configuration;

/**
 * Stichtag der Preisliste ({@code effective_from} oben in prices-base.yml, ISO-8601).
 * Liegt in {@code price_meta} (eine Zeile, UTC) und wird ueber {@code v_price_meta.effective_from}
 * (DATETIME, UTC) fuer den Ingest bereitgestellt. Der Platzhalter 1970-01-01T00:00:00Z bedeutet
 * "nicht gesetzt": dann ist die View leer und change_24h_pct verhaelt sich wie bisher.
 */
final class PriceMeta {
  static final Instant PLACEHOLDER = Instant.EPOCH;
  static final String VIEW = "CREATE OR REPLACE VIEW v_price_meta AS SELECT effective_from FROM price_meta WHERE id=1";
  private PriceMeta() {}

  static Instant effectiveFrom(Configuration prices) {
    String raw = prices.getString("effective_from");
    if (raw == null || raw.isBlank()) return PLACEHOLDER;
    try { return OffsetDateTime.parse(raw.trim()).toInstant(); }
    catch (DateTimeParseException e) { throw new IllegalArgumentException("effective_from ist kein ISO-8601-Zeitstempel mit Zone: " + raw); }
  }
  static void ensureSchema(Statement s) throws SQLException {
    s.execute("CREATE TABLE IF NOT EXISTS price_meta (id TINYINT PRIMARY KEY, effective_from DATETIME NOT NULL, effective_from_epoch BIGINT NOT NULL)");
    s.execute(VIEW);
  }
  static void store(Connection c, Instant effectiveFrom) throws SQLException {
    try (Statement s = c.createStatement()) { ensureSchema(s); s.execute("DELETE FROM price_meta WHERE id=1"); }
    if (effectiveFrom == null || !effectiveFrom.isAfter(PLACEHOLDER)) return;
    try (PreparedStatement q = c.prepareStatement("INSERT INTO price_meta(id,effective_from,effective_from_epoch) VALUES(1,?,?)")) {
      q.setObject(1, LocalDateTime.ofInstant(effectiveFrom, ZoneOffset.UTC));
      q.setLong(2, effectiveFrom.getEpochSecond());
      q.executeUpdate();
    }
  }
}
