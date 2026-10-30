package de.mcstack.craftcore;

import java.sql.*;

/** Same SELECT grant shape as scripts/v-player-stats.sql for creepy_ro. */
final class ReadGrants {
  private ReadGrants() {}
  static String sql(String view) { return "GRANT SELECT ON craftcore."+view+" TO 'creepy_ro'@'%'"; }
  static boolean apply(Statement s, String view) {
    try { s.execute(sql(view)); return true; }
    catch (SQLException e) { return false; }
  }
  static void applyLogged(Statement s, String view, java.util.function.Consumer<String> warn) {
    if (!apply(s, view) && warn != null) warn.accept("Leserecht fuer "+view+" nicht gesetzt.");
  }
}
