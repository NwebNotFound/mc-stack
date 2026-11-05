package de.mcstack.status;

import java.sql.*;

/** The last read before dispatch, on an autocommit connection (never a stale transaction snapshot). */
final class SwitchDispatch {
  static boolean latest(Connection c, long id, String player) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("SELECT MAX(id) FROM proxy_switch_requests WHERE player=?")) {
      q.setString(1, player);
      try (ResultSet r = q.executeQuery()) { return r.next() && r.getLong(1) == id; }
    }
  }
}
