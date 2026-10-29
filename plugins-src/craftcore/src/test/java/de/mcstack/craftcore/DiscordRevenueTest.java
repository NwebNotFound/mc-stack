package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DiscordRevenueTest {
 void exercise(Connection c,boolean temporary) throws Exception {
  {
   Statement s=c.createStatement();s.execute("CREATE "+(temporary?"TEMPORARY ":"")+"TABLE discord_links(uuid VARCHAR(36) PRIMARY KEY,discord_id VARCHAR(20))");s.execute("CREATE "+(temporary?"TEMPORARY ":"")+"TABLE bank_link_revenue(owner VARCHAR(40),day_key VARCHAR(10),cents BIGINT,PRIMARY KEY(owner,day_key))");
   s.execute("INSERT INTO discord_links VALUES('a','123'),('b','123')");c.setAutoCommit(false);
   String owner=DiscordRevenue.lock(c,"a","day");assertTrue(DiscordRevenue.reserve(c,owner,"day",100000,150000));c.commit();
   owner=DiscordRevenue.lock(c,"b","day");assertFalse(DiscordRevenue.reserve(c,owner,"day",50001,150000));assertTrue(DiscordRevenue.reserve(c,owner,"day",50000,150000));c.commit();
   assertEquals(0,DiscordRevenue.remaining(c,"a","day",150000));assertEquals(150000,DiscordRevenue.remaining(c,"other","day",150000));assertEquals(150000,DiscordRevenue.remaining(c,"b","next",150000));
  }
 }
 @Test void h2() throws Exception {try(Connection c=DriverManager.getConnection("jdbc:h2:mem:links;MODE=MySQL")){exercise(c,false);}}
 @Test void mariaDb() throws Exception {String password=System.getenv("CRAFTCORE_DB_PASSWORD");org.junit.jupiter.api.Assumptions.assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password)){exercise(c,true);}}
}
