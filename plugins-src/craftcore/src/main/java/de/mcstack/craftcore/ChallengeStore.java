package de.mcstack.craftcore;
import java.sql.*;
final class ChallengeStore {
 static void advance(Connection c,String uuid,String period,String id,int progress,boolean completed,int cp,int points)throws SQLException {
  int updated;try(PreparedStatement q=c.prepareStatement("UPDATE player_challenges SET progress=?,completed=? WHERE uuid=? AND period_key=? AND challenge_id=? AND completed=FALSE")){q.setInt(1,progress);q.setBoolean(2,completed);q.setString(3,uuid);q.setString(4,period);q.setString(5,id);updated=q.executeUpdate();}
  if(updated!=1)throw new SQLException("Challenge bereits abgeschlossen oder fehlt");
  if(completed){EconomyStore.change(c,uuid,cp*100L);try(PreparedStatement q=c.prepareStatement("INSERT INTO player_season_points(uuid,points) VALUES(?,?) ON DUPLICATE KEY UPDATE points=points+VALUES(points)")){q.setString(1,uuid);q.setInt(2,points);q.executeUpdate();}}
 }
}
