package de.mcstack.craftcore;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class RconPurchaseTest {
 static final String BUYER="00000000-0000-0000-0000-000000000001", SELLER="00000000-0000-0000-0000-000000000002", POOR="00000000-0000-0000-0000-000000000003", ID="00000000-0000-0000-0000-000000000004";
 static RconPurchase.Request req(String kind,String buyer,int qty,long max,String key){return new RconPurchase.Request(kind,buyer,ID,qty,max,key);}
 static String buy(Connection c,RconPurchase.Request r)throws Exception{return RconPurchase.buy(c,r,1,(item,amount)->"rest"+amount,500);}
 static long val(Connection c,String sql)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
 void exercise(Connection c,boolean temporary)throws Exception {
  String t=temporary?"TEMPORARY ":"";
  try(Statement s=c.createStatement()){
   s.execute("CREATE "+t+"TABLE ah_fee_journal(purchase_id VARCHAR(36) PRIMARY KEY,listing_id VARCHAR(36),buyer VARCHAR(36),seller VARCHAR(36),gross BIGINT,fee BIGINT,created_at BIGINT)");

   s.execute("CREATE "+t+"TABLE rcon_purchases(idem_key VARBINARY(64) PRIMARY KEY,kind VARCHAR(8),buyer VARCHAR(36),listing_id VARCHAR(36),qty INT,max_price BIGINT,response VARCHAR(80),created_at BIGINT)");
   s.execute("CREATE "+t+"TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
   s.execute("CREATE "+t+"TABLE purchase_ids(id VARCHAR(36) PRIMARY KEY)");
   s.execute("CREATE "+t+"TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt LONGTEXT,amount INT,source VARCHAR(32),status VARCHAR(16))");
   s.execute("CREATE "+t+"TABLE sale_notices(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),item VARCHAR(64),amount INT,cp BIGINT,sold_at BIGINT,reported BOOLEAN)");
   s.execute("CREATE "+t+"TABLE market_events(item VARCHAR(64),qty INT,unit_price DECIMAL(20,4),total_cp DECIMAL(20,2),source VARCHAR(8),ts BIGINT)");
   s.execute("CREATE "+t+"TABLE shop_events(id VARCHAR(36) PRIMARY KEY,listing_id VARCHAR(36),event_type VARCHAR(24),seller VARCHAR(36),seller_name VARCHAR(16),price BIGINT,actor VARCHAR(36),created_at TIMESTAMP)");
   for(String table:new String[]{"shop_journal","ah_journal"})s.execute("CREATE "+t+"TABLE "+table+"(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),seller_name VARCHAR(16),price BIGINT,status VARCHAR(16),item LONGTEXT,amount INT,expires_at BIGINT,material VARCHAR(64))");
   s.execute("INSERT INTO accounts VALUES('"+BUYER+"',1000),('"+SELLER+"',0),('"+POOR+"',0)");
   for(String table:new String[]{"shop_journal","ah_journal"})s.execute("INSERT INTO "+table+" VALUES('"+ID+"','"+SELLER+"','Seller',100,'OPEN','item',10,1000,'IRON_INGOT')");
  }
  c.setAutoCommit(false);
  for(String kind:new String[]{"shop","ah"}) {
   assertEquals("ERR NO_FUNDS",buy(c,req(kind,POOR,2,100,kind+"poor")));
   assertEquals("ERR SOLD_OUT",buy(c,req(kind,BUYER,11,1000,kind+"stock")));
   assertEquals("ERR PRICE_CHANGED",buy(c,req(kind,BUYER,2,19,kind+"price")));
   assertEquals("ERR SELF_BUY",buy(c,req(kind,SELLER,2,100,kind+"self")));
   assertEquals("ERR NOT_FOUND",buy(c,new RconPurchase.Request(kind,BUYER,UUID.randomUUID().toString(),1,100,kind+"missing")));
   var first=req(kind,BUYER,4,40,kind+"ok");
   assertEquals("OK 4 40 6",buy(c,first)); c.commit();
   assertEquals("OK 4 40 6",buy(c,first));
   assertEquals("ERR DUPLICATE",buy(c,req(kind,BUYER,3,40,kind+"ok")));
   assertEquals("ERR DUPLICATE",buy(c,req(kind,BUYER,4,41,kind+"ok")));
   assertEquals("OK 6 60 0",buy(c,req(kind,BUYER,6,60,kind+"rest")));
   assertEquals("ERR SOLD_OUT",buy(c,req(kind,BUYER,1,100,kind+"sold"))); c.commit();
  }
  assertEquals(995,val(c,"SELECT SUM(balance) FROM accounts"));
  assertEquals(800,val(c,"SELECT balance FROM accounts WHERE uuid='"+BUYER+"'"));
  assertEquals(195,val(c,"SELECT balance FROM accounts WHERE uuid='"+SELLER+"'"));
  assertEquals(5,val(c,"SELECT SUM(fee) FROM ah_fee_journal"));
  assertEquals(4,val(c,"SELECT COUNT(*) FROM pending_deliveries WHERE player_uuid='"+BUYER+"'"));
  assertEquals(4,val(c,"SELECT COUNT(*) FROM sale_notices"));
 }
 @Test void invalid(){String[] good={"buy",BUYER,ID,"1","100","key"}; assertNotNull(RconPurchase.parse("shop",good)); for(int i=1;i<6;i++){String[] bad=good.clone();bad[i]=switch(i){case 1,2 -> "bad";case 3 -> "0";case 4 -> "-1";default -> "!";};assertNull(RconPurchase.parse("shop",bad));} assertNull(RconPurchase.parse("shop",new String[]{"buy"}));String[] bad=good.clone();bad[5]="x".repeat(65);assertNull(RconPurchase.parse("ah",bad));}
 @Test void fakeAccountCannotPayRealSeller()throws Exception {
  String fake="00000000-0000-0000-0000-0000c0de0001", real="00000000-0000-0000-0000-000000000099", listing="00000000-0000-0000-0000-0000000000aa";
  try(Connection c=DriverManager.getConnection("jdbc:h2:mem:fakepay;MODE=MySQL;DB_CLOSE_DELAY=-1")){
   try(Statement s=c.createStatement()){
    s.execute("CREATE TABLE rcon_purchases(idem_key VARBINARY(64) PRIMARY KEY,kind VARCHAR(8),buyer VARCHAR(36),listing_id VARCHAR(36),qty INT,max_price BIGINT,response VARCHAR(80),created_at BIGINT)");
    s.execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
    for(String table:new String[]{"shop_journal","ah_journal"}) s.execute("CREATE TABLE "+table+"(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),seller_name VARCHAR(16),price BIGINT,status VARCHAR(16),item LONGTEXT,amount INT,expires_at BIGINT,material VARCHAR(64))");
    s.execute("INSERT INTO accounts VALUES('"+fake+"',5000),('"+real+"',100)");
    for(String table:new String[]{"shop_journal","ah_journal"}) s.execute("INSERT INTO "+table+" VALUES('"+listing+"','"+real+"','Real',100,'OPEN','item',10,1000,'IRON_INGOT')");
   }
   c.setAutoCommit(false);
   for(String kind:new String[]{"shop","ah"}){
    var request=new RconPurchase.Request(kind,fake,listing,1,100,kind+"-leak");
    assertEquals("ERR INVALID",buy(c,request)); c.commit();
    assertEquals("ERR INVALID",buy(c,request)); c.commit();
    assertEquals(5000,val(c,"SELECT balance FROM accounts WHERE uuid='"+fake+"'"));
    assertEquals(100,val(c,"SELECT balance FROM accounts WHERE uuid='"+real+"'"));
   }
  }
 }
 @Test void h2()throws Exception{try(Connection c=DriverManager.getConnection("jdbc:h2:mem:rcon;MODE=MySQL;DB_CLOSE_DELAY=-1")){exercise(c,false);}}
 @Test void mariaDb()throws Exception{String password=System.getenv("CRAFTCORE_DB_PASSWORD");assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password)){exercise(c,true);}}
}
