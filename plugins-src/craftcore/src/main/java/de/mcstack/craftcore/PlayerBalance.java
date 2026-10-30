package de.mcstack.craftcore;

import java.sql.*;
import java.util.Locale;

/**
 * Exposes the real account balance. accounts.balance is cents;
 * balance_cp is that value divided by 100, same as v_player_stats.money.
 * On MariaDB, updated_at follows every UPDATE of the account row.
 */
final class PlayerBalance {
  static final String VIEW = "CREATE OR REPLACE VIEW v_player_balance AS SELECT a.uuid, p.name, CAST(a.balance / 100.0 AS DECIMAL(21,2)) AS balance_cp, a.updated_at FROM accounts a LEFT JOIN known_players p ON p.uuid=a.uuid";
  static final String MODIFY_TIMESTAMP = "ALTER TABLE accounts MODIFY updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
  record TimestampColumn(String nullable, String definition, String extra) {}
  private PlayerBalance() {}
  static String timestampDdl(String product) {
    boolean h2 = product != null && product.toLowerCase(Locale.ROOT).contains("h2");
    String type = h2 ? "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" : "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
    return "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS updated_at "+type;
  }
  static boolean needsAlter(TimestampColumn column, boolean onUpdate) {
    if (column == null) return true;
    if (column.nullable() == null || !"NO".equalsIgnoreCase(column.nullable())) return true;
    String definition = column.definition() == null ? "" : column.definition().toLowerCase(Locale.ROOT);
    if (!definition.contains("current_timestamp")) return true;
    if (!onUpdate) return false;
    String extra = column.extra() == null ? "" : column.extra().toLowerCase(Locale.ROOT);
    return !extra.contains("on update");
  }
  static TimestampColumn readTimestamp(Connection c) throws SQLException {
    boolean h2 = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2");
    if (!h2) {
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT IS_NULLABLE, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND LOWER(TABLE_NAME)='accounts' AND LOWER(COLUMN_NAME)='updated_at'")) {
        if (!r.next()) return null;
        return new TimestampColumn(r.getString(1), r.getString(2), r.getString(3));
      }
    }
    for (String table : new String[]{"ACCOUNTS", "accounts"}) {
      for (String column : new String[]{"UPDATED_AT", "updated_at"}) {
        try (ResultSet r = c.getMetaData().getColumns(null, null, table, column)) {
          if (r.next()) return new TimestampColumn(r.getString("IS_NULLABLE"), r.getString("COLUMN_DEF"), "");
        }
      }
    }
    return null;
  }
  static void migrate(Connection c) throws SQLException {
    String product = c.getMetaData().getDatabaseProductName();
    boolean h2 = product != null && product.toLowerCase(Locale.ROOT).contains("h2");
    try (Statement s = c.createStatement()) {
      TimestampColumn column = readTimestamp(c);
      if (column == null) s.execute(timestampDdl(product));
      else if (!h2 && needsAlter(column, true)) s.execute(MODIFY_TIMESTAMP);
      s.execute(VIEW);
    }
  }
}
