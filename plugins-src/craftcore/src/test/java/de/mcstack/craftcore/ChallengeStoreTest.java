package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class ChallengeStoreTest {
 @Test void completionIsAtomicAndCannotPayTwice() throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:h2:mem:challenge;MODE=MySQL;DB_CLOSE_DELAY=-1")){try(Statement s=c.createStatement()){s.execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");s.execute("CREATE TABLE player_season_points(uuid VARCHAR(36) PRIMARY KEY,points BIGINT NOT NULL)");s.execute("CREATE TABLE player_challenges(uuid VARCHAR(36),period_key VARCHAR(12),challenge_id VARCHAR(64),progress INT,completed BOOLEAN,PRIMARY KEY(uuid,period_key,challenge_id))");s.execute("INSERT INTO player_challenges VALUES('p','D:2026-10-28','sheep',0,FALSE)");}c.setAutoCommit(false);
   ChallengeStore.advance(c,"p","D:2026-10-28","sheep",5,true,30,10);c.rollback();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT progress,completed FROM player_challenges")){assertTrue(r.next());assertEquals(0,r.getInt(1));assertFalse(r.getBoolean(2));}
   ChallengeStore.advance(c,"p","D:2026-10-28","sheep",5,true,30,10);c.commit();assertThrows(SQLException.class,()->ChallengeStore.advance(c,"p","D:2026-10-28","sheep",5,true,30,10));c.rollback();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT balance,(SELECT points FROM player_season_points WHERE uuid='p') FROM accounts WHERE uuid='p'")){assertTrue(r.next());assertEquals(3000,r.getLong(1));assertEquals(10,r.getInt(2));}
  }
 }
}
