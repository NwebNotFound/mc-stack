package de.mcstack.craftcore;
import java.sql.*;
import java.util.*;
final class InviteStore {
 static final String DDL="CREATE TABLE IF NOT EXISTS invites (id BIGINT AUTO_INCREMENT PRIMARY KEY, inviter_uuid CHAR(36) NOT NULL, invitee_uuid CHAR(36) NOT NULL UNIQUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, rewarded BOOLEAN NOT NULL DEFAULT FALSE, paid_at TIMESTAMP NULL)";
 static void prepareSchema(Connection c) throws SQLException {
  boolean legacy=false; try(ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,"invites","team_id")){legacy=r.next();}
  if(legacy) { try(Statement s=c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS team_invites (id CHAR(36) PRIMARY KEY, uuid CHAR(36) NOT NULL, team_id BIGINT NOT NULL, UNIQUE KEY invite_target (uuid,team_id), FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE)"); s.execute("INSERT IGNORE INTO team_invites(id,uuid,team_id) SELECT UUID(),uuid,team_id FROM invites"); s.execute("RENAME TABLE invites TO invites_legacy_migrated"); } }
  try(Statement s=c.createStatement()){s.execute(DDL);s.execute("CREATE TABLE IF NOT EXISTS invite_reward_notices (invite_id BIGINT PRIMARY KEY, inviter_uuid CHAR(36) NOT NULL, cp BIGINT NOT NULL, reported BOOLEAN NOT NULL DEFAULT FALSE)");}
 }
 static List<Long> eligible(Connection c,long seconds)throws SQLException {List<Long> ids=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT i.id FROM invites i JOIN motivation m ON m.uuid=i.invitee_uuid WHERE i.rewarded=FALSE AND m.seconds_played>=? ORDER BY i.id")){q.setLong(1,seconds);try(ResultSet r=q.executeQuery()){while(r.next())ids.add(r.getLong(1));}}return ids;}
 static String reward(Connection c,long id,long seconds,long cents)throws SQLException {
  try(PreparedStatement q=c.prepareStatement("SELECT inviter_uuid,invitee_uuid,rewarded FROM invites WHERE id=? FOR UPDATE")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getBoolean(3))return null;String inviter=r.getString(1),invitee=r.getString(2);try(PreparedStatement m=c.prepareStatement("SELECT seconds_played FROM motivation WHERE uuid=?")){m.setString(1,invitee);try(ResultSet t=m.executeQuery()){if(!t.next()||t.getLong(1)<seconds)return null;}} if(!EconomyStore.change(c,inviter,cents))throw new SQLException("Gutschrift fehlgeschlagen");try(PreparedStatement u=c.prepareStatement("UPDATE invites SET rewarded=TRUE,paid_at=CURRENT_TIMESTAMP WHERE id=? AND rewarded=FALSE")){u.setLong(1,id);if(u.executeUpdate()!=1)throw new SQLException("Einladung geändert");}try(PreparedStatement n=c.prepareStatement("INSERT INTO invite_reward_notices(invite_id,inviter_uuid,cp) VALUES(?,?,?)")){n.setLong(1,id);n.setString(2,inviter);n.setLong(3,cents);n.executeUpdate();}return inviter;}}
 }
}
