package de.mcstack.craftcore;

import java.sql.*;

/** Same SELECT grant shape as PurchaseViewGrant. A failure must not stop startup. */
final class ReadGrants {
  private ReadGrants() {}
  static String sql(String view) { return "GRANT SELECT ON craftcore."+view+" TO 'creepy_ro'@'%'"; }
  static boolean apply(Connection c, String view) {
    try (Statement s = c.createStatement()) {
      s.execute(sql(view));
      return true;
    } catch (SQLException ex) {
      return false;
    }
  }
}
