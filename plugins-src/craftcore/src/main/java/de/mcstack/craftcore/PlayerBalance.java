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
  private PlayerBalance() {}
  static String timestampDdl(String product) {
    boolean h2 = product != null && product.toLowerCase(Locale.ROOT).contains("h2");
    String type = h2 ? "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" : "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
    return "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS updated_at "+type;
  }
  static void migrate(Connection c) throws SQLException {
    String product = c.getMetaData().getDatabaseProductName();
    try (Statement s = c.createStatement()) {
      s.execute(timestampDdl(product));
      if (product != null && !product.toLowerCase(Locale.ROOT).contains("h2")) {
        s.execute("ALTER TABLE accounts MODIFY updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
      }
      s.execute(VIEW);
      ReadGrants.apply(s, "v_player_balance");
    }
  }
}
