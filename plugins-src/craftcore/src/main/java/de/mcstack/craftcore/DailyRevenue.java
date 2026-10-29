package de.mcstack.craftcore;

import java.sql.*;
import java.time.*;

/** Sale revenue is counted in cents in the same transaction as the bank journal. */
final class DailyRevenue {
  static String dayKey(Instant now, ZoneId zone, int resetHour) {
    if(resetHour<0 || resetHour>23) throw new IllegalArgumentException("Ungueltige Resetstunde");
    ZonedDateTime local=now.atZone(zone);
    return (local.getHour()<resetHour ? local.toLocalDate().minusDays(1) : local.toLocalDate()).toString();
  }
  static long remaining(Connection c,String player,String day,long limit) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("SELECT cents FROM bank_revenue_daily WHERE player=? AND day_key=?")) {
      s.setString(1,player);s.setString(2,day);
      try(ResultSet r=s.executeQuery()) { return r.next() ? Math.max(0,limit-r.getLong(1)) : limit; }
    }
  }
  static void lock(Connection c,String player,String day) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO bank_revenue_daily(player,day_key,cents) VALUES(?,?,0)")) {s.setString(1,player);s.setString(2,day);s.executeUpdate();}
    try(PreparedStatement s=c.prepareStatement("SELECT cents FROM bank_revenue_daily WHERE player=? AND day_key=? FOR UPDATE")) {s.setString(1,player);s.setString(2,day);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Tageslimit fehlt");}}
  }
  static boolean reserve(Connection c,String player,String day,long cents,long limit) throws SQLException {
    if(cents<1 || cents>remaining(c,player,day,limit)) return false;
    try(PreparedStatement s=c.prepareStatement("UPDATE bank_revenue_daily SET cents=cents+? WHERE player=? AND day_key=?")) {s.setLong(1,cents);s.setString(2,player);s.setString(3,day);if(s.executeUpdate()!=1)throw new SQLException("Tageslimit fehlt");}
    return true;
  }
}
