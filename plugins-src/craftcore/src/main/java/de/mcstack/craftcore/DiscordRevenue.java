package de.mcstack.craftcore;
import java.sql.*;

final class DiscordRevenue {
  static String owner(Connection c,String uuid,boolean lock) throws SQLException {
    if(lock) try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO discord_links(uuid,discord_id) VALUES (?,NULL)")){s.setString(1,uuid);s.executeUpdate();}
    try(PreparedStatement s=c.prepareStatement("SELECT discord_id FROM discord_links WHERE uuid=?"+(lock?" FOR UPDATE":""))){s.setString(1,uuid);try(ResultSet r=s.executeQuery()){if(r.next()){String id=r.getString(1);if(id!=null)return "d:"+id;}}}
    return "u:"+uuid;
  }
  static long remaining(Connection c,String uuid,String day,long limit) throws SQLException {return remainingOwner(c,owner(c,uuid,false),day,limit);}
  static long remainingOwner(Connection c,String owner,String day,long limit) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("SELECT cents FROM bank_link_revenue WHERE owner=? AND day_key=?")){s.setString(1,owner);s.setString(2,day);try(ResultSet r=s.executeQuery()){return r.next()?Math.max(0,limit-r.getLong(1)):limit;}}
  }
  static String lock(Connection c,String uuid,String day) throws SQLException {
    String owner=owner(c,uuid,true);
    try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO bank_link_revenue(owner,day_key,cents) VALUES(?,?,0)")){s.setString(1,owner);s.setString(2,day);s.executeUpdate();}
    try(PreparedStatement s=c.prepareStatement("SELECT cents FROM bank_link_revenue WHERE owner=? AND day_key=? FOR UPDATE")){s.setString(1,owner);s.setString(2,day);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Tageslimit fehlt");}}
    return owner;
  }
  static boolean reserve(Connection c,String owner,String day,long cents,long limit) throws SQLException {
    if(cents<1||cents>remainingOwner(c,owner,day,limit))return false;
    try(PreparedStatement s=c.prepareStatement("UPDATE bank_link_revenue SET cents=cents+? WHERE owner=? AND day_key=?")){s.setLong(1,cents);s.setString(2,owner);s.setString(3,day);if(s.executeUpdate()!=1)throw new SQLException("Tageslimit fehlt");}return true;
  }
  private DiscordRevenue() {}
}
