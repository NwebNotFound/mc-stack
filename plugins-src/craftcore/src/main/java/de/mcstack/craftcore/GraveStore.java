package de.mcstack.craftcore;
import java.sql.*;
final class GraveStore {
  static boolean lock(Connection c,long id,long now) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("UPDATE graves SET status='CLAIMING', claiming_at=? WHERE id=? AND status='OPEN'")) {
      s.setLong(1,now);s.setLong(2,id);return s.executeUpdate()==1;
    }
  }
  static void finish(Connection c,long id) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("UPDATE graves SET status='CLAIMED', delivered=TRUE WHERE id=? AND status='CLAIMING' AND delivered=FALSE")) {
      s.setLong(1,id);if(s.executeUpdate()!=1)throw new SQLException("Grababschluss fehlgeschlagen: "+id);
    }
  }
}
