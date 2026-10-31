package de.mcstack.craftcore;

import java.sql.*;

final class SidebarPreference {
  private SidebarPreference() {}
  static void prepare(Connection c) throws SQLException {
    try (Statement s=c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS sidebar_preferences (uuid CHAR(36) PRIMARY KEY, enabled BOOLEAN NOT NULL)"); }
  }
  static boolean enabled(Connection c,String uuid) throws SQLException {
    try (PreparedStatement s=c.prepareStatement("SELECT enabled FROM sidebar_preferences WHERE uuid=?")) { s.setString(1,uuid); try(ResultSet r=s.executeQuery()) { return !r.next() || r.getBoolean(1); } }
  }
  static void set(Connection c,String uuid,boolean enabled) throws SQLException {
    try (PreparedStatement d=c.prepareStatement("DELETE FROM sidebar_preferences WHERE uuid=?")) { d.setString(1,uuid); d.executeUpdate(); }
    try (PreparedStatement s=c.prepareStatement("INSERT INTO sidebar_preferences (uuid,enabled) VALUES (?,?)")) { s.setString(1,uuid); s.setBoolean(2,enabled); s.executeUpdate(); }
  }
}
