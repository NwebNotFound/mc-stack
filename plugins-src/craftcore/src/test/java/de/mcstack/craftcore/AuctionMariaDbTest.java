package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
class AuctionMariaDbTest {
 @Test void priceChangeIsRejectedUnderLock() throws Exception {
  String password=System.getenv("CRAFTCORE_DB_PASSWORD");
  assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
  try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password); Statement s=c.createStatement()) {
   s.execute("CREATE TEMPORARY TABLE ah_fee_journal(purchase_id VARCHAR(36) PRIMARY KEY,listing_id VARCHAR(36),buyer VARCHAR(36),seller VARCHAR(36),gross BIGINT,fee BIGINT,created_at BIGINT)");
   s.execute("CREATE TEMPORARY TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
   s.execute("CREATE TEMPORARY TABLE ah_journal(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),price BIGINT,status VARCHAR(16),recipient VARCHAR(36),expires_at BIGINT,item LONGTEXT,amount INT)");
   s.execute("CREATE TEMPORARY TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt LONGTEXT,amount INT,source VARCHAR(32),status VARCHAR(16))");
   s.execute("INSERT INTO accounts VALUES('seller',0),('buyer',10000)");
   s.execute("INSERT INTO ah_journal VALUES('j','seller',1200,'OPEN',NULL,100,'item',1)");
   c.setAutoCommit(false);
   assertNotNull(AuctionStore.buy(c,"j","buyer",99,500,1000L,"item"));
   try(ResultSet r=s.executeQuery("SELECT balance FROM accounts WHERE uuid='buyer'")){assertTrue(r.next());assertEquals(10000,r.getLong(1));}
   assertNull(AuctionStore.buy(c,"j","buyer",99,500,1200L,"item"));
   assertNotNull(AuctionStore.buy(c,"j","buyer",99,500,1200L,"item"));
   c.rollback();
  }
 }
}
