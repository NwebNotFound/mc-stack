package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
class RealAuctionsMariaDbTest {
 @Test void schemaAndViews() throws Exception {
  String password=System.getenv("CRAFTCORE_DB_PASSWORD"); assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
  try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password);Statement s=c.createStatement()){RealAuctions.schema(c);try(ResultSet r=s.executeQuery("SELECT id,item,menge,seller,min_price,current_bid,current_bidder,bid_count,ends_at,status,created_at,source_last_bid,min_next_bid FROM v_auctions")){assertFalse(r.next());}try(ResultSet r=s.executeQuery("SELECT id,auction_id,bidder,amount,created_at,source FROM v_auction_bids")){assertFalse(r.next());}}
 }
 @Test void commandValidation(){String u="00000000-0000-0000-0000-000000000001";assertNotNull(RealAuctions.parse(new String[]{"bid","1",u,"100","test"}));assertNull(RealAuctions.parse(new String[]{"bid","0",u,"100","test"}));assertNull(RealAuctions.parse(new String[]{"bid","1",u,"100","test","evil"}));}

 @Test void escrowRefundIdempotencyAndOfflineSettlement() throws Exception {
  String password=System.getenv("CRAFTCORE_DB_PASSWORD"); assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
  try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password);Statement s=c.createStatement()){
   s.execute("CREATE TEMPORARY TABLE auctions(id BIGINT PRIMARY KEY,seller VARCHAR(36),item LONGTEXT,material VARCHAR(64),amount INT,min_price BIGINT,current_bid BIGINT,current_bidder VARCHAR(36),bid_count INT,ends_at BIGINT,status VARCHAR(16),created_at BIGINT,source_last_bid VARCHAR(16))");
   s.execute("CREATE TEMPORARY TABLE auction_bids(id BIGINT AUTO_INCREMENT PRIMARY KEY,auction_id BIGINT,bidder VARCHAR(36),amount BIGINT,created_at BIGINT,source VARCHAR(16))");
   s.execute("CREATE TEMPORARY TABLE auction_escrow(auction_id BIGINT PRIMARY KEY,bidder VARCHAR(36),amount BIGINT)");
   s.execute("CREATE TEMPORARY TABLE auction_requests(request_id VARBINARY(64) PRIMARY KEY,auction_id BIGINT,bidder VARCHAR(36),amount BIGINT,source VARCHAR(16),response VARCHAR(128))");
   s.execute("CREATE TEMPORARY TABLE auction_alerts(id BIGINT AUTO_INCREMENT PRIMARY KEY,auction_id BIGINT,bidder VARCHAR(36),reason VARCHAR(32),created_at BIGINT)");
   s.execute("CREATE TEMPORARY TABLE discord_links(uuid VARCHAR(36) PRIMARY KEY,discord_id VARCHAR(20))");
   s.execute("CREATE TEMPORARY TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT)");
   s.execute("CREATE TEMPORARY TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt LONGTEXT,amount INT,source VARCHAR(32),status VARCHAR(16))");
   s.execute("INSERT INTO accounts VALUES('seller',0),('first',10000),('second',10000),('linked',10000)");
   s.execute("INSERT INTO discord_links VALUES('seller','42'),('linked','42')");
   s.execute("INSERT INTO auctions VALUES(1,'seller','serialized','STONE',3,1000,NULL,NULL,0,200000,'OPEN',0,NULL),(2,'seller','serialized','STONE',3,1000,NULL,NULL,0,10,'OPEN',0,NULL)");c.setAutoCommit(false);
   assertEquals("ERR NOT_FOUND",RealAuctions.bid(c,new RealAuctions.Request(999999999,"first",100,"missing","ingame"),100));
   assertEquals("ERR NO_FUNDS",RealAuctions.bid(c,new RealAuctions.Request(1,"first",10001,"poor","ingame"),100));
   assertEquals("ERR SELF_BID",RealAuctions.bid(c,new RealAuctions.Request(1,"seller",1000,"self","ingame"),100));
   assertEquals("ERR LINKED_ACCOUNT",RealAuctions.bid(c,new RealAuctions.Request(1,"linked",1000,"link","discord"),100));
   var one=new RealAuctions.Request(1,"first",1000,"one","web");String first=RealAuctions.bid(c,one,100);assertTrue(first.startsWith("OK"));assertEquals(first,RealAuctions.bid(c,one,200));
   assertEquals("ERR DUPLICATE",RealAuctions.bid(c,new RealAuctions.Request(1,"second",1000,"one","web"),200));
   assertEquals("ERR BID_TOO_LOW",RealAuctions.bid(c,new RealAuctions.Request(1,"second",1000,"tie","ingame"),201));
   assertEquals("ERR BID_TOO_LOW",RealAuctions.bid(c,new RealAuctions.Request(1,"second",1099,"low","ingame"),202));
   assertTrue(RealAuctions.bid(c,new RealAuctions.Request(1,"second",1100,"two","discord"),100000).startsWith("OK"));
   assertEquals(10000,scalar(s,"SELECT balance FROM accounts WHERE uuid='first'"));assertEquals(8900,scalar(s,"SELECT balance FROM accounts WHERE uuid='second'"));assertEquals(1100,scalar(s,"SELECT amount FROM auction_escrow WHERE auction_id=1"));assertEquals(320000,scalar(s,"SELECT ends_at FROM auctions WHERE id=1"));assertEquals(2,scalar(s,"SELECT COUNT(*) FROM auction_bids"));
   RealAuctions.settle(c,1,320000);RealAuctions.settle(c,1,320000);RealAuctions.settle(c,2,320000);
   assertEquals(1067,scalar(s,"SELECT balance FROM accounts WHERE uuid='seller'"));assertEquals(0,scalar(s,"SELECT COUNT(*) FROM auction_escrow"));assertEquals(1,scalar(s,"SELECT COUNT(*) FROM pending_deliveries WHERE player_uuid='second'"));assertEquals(1,scalar(s,"SELECT COUNT(*) FROM pending_deliveries WHERE player_uuid='seller'"));c.rollback();
  }
 }
 static long scalar(Statement s,String q)throws SQLException{try(ResultSet r=s.executeQuery(q)){r.next();return r.getLong(1);}}
}
