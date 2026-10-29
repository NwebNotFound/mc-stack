package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.*;
class InviteMariaDbTest {
 @Test void thresholdAndRepeat()throws Exception {String pass=System.getenv("CRAFTCORE_DB_PASSWORD");assumeTrue(pass!=null&&"true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",pass);Statement s=c.createStatement()) {s.execute("CREATE TEMPORARY TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");s.execute("CREATE TEMPORARY TABLE invite_reward_notices(invite_id BIGINT PRIMARY KEY,inviter_uuid VARCHAR(36),cp BIGINT,reported BOOLEAN DEFAULT FALSE)");s.execute("CREATE TEMPORARY TABLE motivation(uuid VARCHAR(36) PRIMARY KEY,seconds_played BIGINT NOT NULL)");s.execute(InviteStore.DDL.replace("CREATE TABLE", "CREATE TEMPORARY TABLE"));s.execute("INSERT INTO accounts VALUES('inviter',123)");s.execute("INSERT INTO motivation VALUES('guest',7199)");s.execute("INSERT INTO invites(inviter_uuid,invitee_uuid) VALUES('inviter','guest')");InviteStoreTest.check(c);}}
}
