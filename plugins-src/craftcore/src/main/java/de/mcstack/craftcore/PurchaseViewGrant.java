package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Gives creepy_ro read access to the purchase-result view. A failure must not stop startup. */
final class PurchaseViewGrant {
  static final String SQL = "GRANT SELECT ON craftcore.v_rcon_purchase_result TO 'creepy_ro'@'%'";

  /** Relay reads bank_buy results (order type bank_buy) after repeated PENDING. */
  static final String BANK_SQL = "GRANT SELECT ON craftcore.bank_buy_orders TO 'creepy_ro'@'%'";
  static boolean apply(Connection c) { return run(c, SQL); }
  static boolean applyBank(Connection c) { return run(c, BANK_SQL); }

  private static boolean run(Connection c, String sql) {
    try (Statement s = c.createStatement()) {
      s.execute(sql);
      return true;
    } catch (SQLException ex) {
      return false;
    }
  }

  private PurchaseViewGrant() {}
}
