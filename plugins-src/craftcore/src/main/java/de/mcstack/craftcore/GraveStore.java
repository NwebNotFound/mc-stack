package de.mcstack.craftcore;
import java.sql.*;
import java.util.*;
final class GraveStore {
  static boolean lock(Connection c,long id,long now) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("UPDATE graves SET status='CLAIMING', claiming_at=? WHERE id=? AND status='OPEN'")) {
      s.setLong(1,now);s.setLong(2,id);return s.executeUpdate()==1;
    }
  }
  /** One item of the grave, already serialised on the main thread (ItemStack.serializeAsBytes, Base64). */
  record Loot(String data,int amount) {}
  /**
   * Close-out and durable hand-over in ONE transaction (Gatekeeper #170): the grave becomes CLAIMED, every item
   * goes into the claimer's pickup inbox (pending_deliveries, source "grave") and the XP is recorded as owed
   * (xp_paid=FALSE). Items are then handed out from the inbox and the XP from the grave row, so a crash or a
   * disconnect after the commit loses nothing. Returns the delivery ids.
   */
  static List<String> finish(Connection c,long id,String claimer,List<Loot> loot,int xp) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("UPDATE graves SET status='CLAIMED', delivered=TRUE, claimed_by=?, xp_paid=? WHERE id=? AND status='CLAIMING' AND delivered=FALSE")) {
      s.setString(1,claimer);s.setBoolean(2,xp<=0);s.setLong(3,id);if(s.executeUpdate()!=1)throw new SQLException("Grababschluss fehlgeschlagen: "+id);
    }
    List<String> ids=new ArrayList<>();
    for(Loot l:loot){String did=UUID.randomUUID().toString();Deliveries.insertRaw(c,did,claimer,l.data(),l.amount(),"grave");ids.add(did);}
    return ids;
  }
  /** Marks the grave's XP as paid; true only for the call that flipped it. */
  static boolean xpPaid(Connection c,long id) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("UPDATE graves SET xp_paid=TRUE WHERE id=? AND xp_paid=FALSE")){s.setLong(1,id);return s.executeUpdate()==1;}
  }
  /** Graves of this server whose XP the player was never given (crash or disconnect before the hand-out): [id, xp]. */
  static List<long[]> unpaidXp(Connection c,String server,String player) throws SQLException {
    List<long[]> out=new ArrayList<>();
    try(PreparedStatement s=c.prepareStatement("SELECT id,xp FROM graves WHERE server=? AND claimed_by=? AND status='CLAIMED' AND xp_paid=FALSE ORDER BY id")){s.setString(1,server);s.setString(2,player);try(ResultSet r=s.executeQuery()){while(r.next())out.add(new long[]{r.getLong(1),r.getLong(2)});}}
    return out;
  }
}
