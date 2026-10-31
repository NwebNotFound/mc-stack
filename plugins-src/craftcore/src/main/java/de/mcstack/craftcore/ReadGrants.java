package de.mcstack.craftcore;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Same SELECT grant shape as PurchaseViewGrant. A failure must not stop startup. */
final class ReadGrants {
  private ReadGrants() {}
  static boolean hasGrantOption(List<String> grants) {
    return grants.stream().anyMatch(grant -> grant != null && grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION"));
  }
  static boolean canGrant(Connection c) {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
      List<String> grants = new ArrayList<>();
      while (r.next()) grants.add(r.getString(1));
      return hasGrantOption(grants);
    } catch (Exception ex) {
      return false;
    }
  }
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
