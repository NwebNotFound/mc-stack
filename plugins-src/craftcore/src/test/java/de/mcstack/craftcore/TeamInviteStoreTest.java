package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class TeamInviteStoreTest {
  public static long epoch(){return System.currentTimeMillis()/1000;}
  Connection setup(String db) throws Exception {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+db+";MODE=MySQL;DB_CLOSE_DELAY=-1");
    try(Statement s=c.createStatement()){
      s.execute("CREATE TABLE teams(id BIGINT PRIMARY KEY,name VARCHAR(16))");
      s.execute("CREATE TABLE members(uuid VARCHAR(36) PRIMARY KEY,team_id BIGINT,role VARCHAR(8))");
      s.execute("CREATE TABLE online_players(uuid VARCHAR(36) PRIMARY KEY,name VARCHAR(16),server VARCHAR(64),seen_at BIGINT)");
      s.execute("CREATE TABLE team_invites(id VARCHAR(36) PRIMARY KEY,uuid VARCHAR(36),team_id BIGINT, UNIQUE(uuid,team_id))");
      s.execute("INSERT INTO teams VALUES(1,'Alpha'),(2,'Beta')");
      s.execute("INSERT INTO members VALUES('owner',1,'OWNER'),('member',1,'MEMBER'),('owner2',2,'OWNER')");
      s.execute("INSERT INTO online_players VALUES('target','Target','survival',UNIX_TIMESTAMP()),('stale','Stale','lobby',UNIX_TIMESTAMP()-30)");
    } return c;
  }
  String scalar(Connection c,String sql)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){return r.next()?r.getString(1):null;}}
  @Test void crossServerInviteAcceptAndRepeat() throws Exception {try(Connection c=setup("invite1")){
    assertTrue(TeamInviteStore.apply(c,"owner","invite","Target").contains("erstellt"));
    String first=scalar(c,"SELECT id FROM team_invites WHERE uuid='target'");
    assertTrue(TeamInviteStore.apply(c,"target","accept",first).contains("Alpha"));
    assertEquals("1",scalar(c,"SELECT team_id FROM members WHERE uuid='target'"));
    assertTrue(TeamInviteStore.apply(c,"target","accept",first).contains("nicht mehr"));
  }}
  @Test void declineReinviteOwnershipAndLegacyJoin() throws Exception {try(Connection c=setup("invite2")){
    TeamInviteStore.apply(c,"owner","invite","Target"); String first=scalar(c,"SELECT id FROM team_invites");
    assertTrue(TeamInviteStore.apply(c,"stranger","accept",first).contains("nicht mehr"));
    TeamInviteStore.apply(c,"owner","invite","Target"); String second=scalar(c,"SELECT id FROM team_invites"); assertNotEquals(first,second);
    assertTrue(TeamInviteStore.apply(c,"target","decline",second).contains("abgelehnt"));
    TeamInviteStore.apply(c,"owner2","invite","Target"); assertTrue(TeamInviteStore.apply(c,"target","join","Beta").contains("Beta"));
  }}
  @Test void offlineAndPermissions() throws Exception {try(Connection c=setup("invite3")){
    assertTrue(TeamInviteStore.apply(c,"owner","invite","Stale").contains("nicht im Netzwerk"));
    assertTrue(TeamInviteStore.apply(c,"member","invite","Target").contains("Owner"));
    assertNull(scalar(c,"SELECT id FROM team_invites"));
  }}
}

