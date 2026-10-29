package de.mcstack.craftcore;
import java.sql.*;
import java.time.LocalDate;
final class MotivationStore {
 record State(LocalDate day,int streak,long seconds,long blocks,long kills,boolean badge7,boolean badge30,int badge) {}
 static State lock(Connection c,String id)throws SQLException {
  try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO motivation(uuid) VALUES(?)")){q.setString(1,id);q.executeUpdate();}
  try(PreparedStatement q=c.prepareStatement("SELECT login_day,streak,seconds_played,blocks,kills,badge7,badge30,badge FROM motivation WHERE uuid=? FOR UPDATE")){q.setString(1,id);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Fortschritt fehlt");Date d=r.getDate(1);return new State(d==null?null:d.toLocalDate(),r.getInt(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getBoolean(6),r.getBoolean(7),r.getInt(8));}}
 }
 static int nextStreak(LocalDate last,int streak,LocalDate today){if(last==null)return 1;if(!today.isAfter(last))return 0;return today.equals(last.plusDays(1))?streak+1:1;}
 static int login(Connection c,String id,LocalDate today)throws SQLException {State s=lock(c,id);int n=nextStreak(s.day,s.streak,today);if(n==0)return 0;try(PreparedStatement q=c.prepareStatement("UPDATE motivation SET login_day=?,streak=?,badge7=?,badge30=? WHERE uuid=?")){q.setDate(1,Date.valueOf(today));q.setInt(2,n);q.setBoolean(3,s.badge7||n>=7);q.setBoolean(4,s.badge30||n>=30);q.setString(5,id);q.executeUpdate();}EconomyStore.change(c,id,1000);return n;}
 static String rank(long seconds){if(seconds>=300*3600L)return "Legende";if(seconds>=100*3600L)return "Veteran";if(seconds>=25*3600L)return "Siedler";if(seconds>=5*3600L)return "Neuling";return "";}
 static long crossed(long before,long after,long[] limits){long result=0;for(long n:limits)if(before<n&&after>=n)result=n;return result;}
}
