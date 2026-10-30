package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Gives creepy_ro read access to the purchase-result view. A failure must not stop startup. */
final class PurchaseViewGrant {
  static final String SQL = "GRANT SELECT ON craftcore.v_rcon_purchase_result TO 'creepy_ro'@'%'";

  static boolean apply(Connection c) {
    try (Statement s = c.createStatement()) {
      s.execute(SQL);
      return true;
    } catch (SQLException ex) {
      return false;
    }
  }

  private PurchaseViewGrant() {}
}
