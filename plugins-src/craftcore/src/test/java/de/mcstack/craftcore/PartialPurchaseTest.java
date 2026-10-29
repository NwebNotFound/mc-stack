package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class PartialPurchaseTest {
 void exercise(Connection c, boolean temporary)throws Exception {
  try(Statement s=c.createStatement()){
   String t=temporary?"TEMPORARY ":"";
   s.execute("CREATE "+t+"TABLE ah_fee_journal(purchase_id VARCHAR(36) PRIMARY KEY,listing_id VARCHAR(36),buyer VARCHAR(36),seller VARCHAR(36),gross BIGINT,fee BIGINT,created_at BIGINT)");
   s.execute("CREATE "+t+"TABLE discord_links(uuid VARCHAR(36) PRIMARY KEY,discord_id VARCHAR(20))");
   s.execute("CREATE "+t+"TABLE bank_link_revenue(owner VARCHAR(40),day_key VARCHAR(10),cents BIGINT,PRIMARY KEY(owner,day_key))");
   s.execute("INSERT INTO discord_links VALUES('seller','123')");
   s.execute("INSERT INTO bank_link_revenue VALUES('d:123','day',150000)");
   s.execute("CREATE "+t+"TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
   s.execute("CREATE "+t+"TABLE purchase_ids(id VARCHAR(36) PRIMARY KEY)");
   s.execute("CREATE "+t+"TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt LONGTEXT,amount INT,source VARCHAR(32),status VARCHAR(16))");
   s.execute("CREATE "+t+"TABLE sale_notices(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),item VARCHAR(64),amount INT,cp BIGINT,sold_at BIGINT,reported BOOLEAN)");
   s.execute("CREATE "+t+"TABLE market_events(item VARCHAR(64),qty INT,unit_price DECIMAL(20,4),total_cp DECIMAL(20,2),source VARCHAR(8),ts BIGINT)");
   s.execute("CREATE "+t+"TABLE shop_notifications(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),listing_id VARCHAR(36),buyer VARCHAR(36),price BIGINT,delivered BOOLEAN)");
   s.execute("CREATE "+t+"TABLE shop_events(id VARCHAR(36) PRIMARY KEY,listing_id VARCHAR(36),event_type VARCHAR(24),seller VARCHAR(36),seller_name VARCHAR(16),price BIGINT,actor VARCHAR(36),created_at TIMESTAMP)");
   for(String table:new String[]{"shop_journal","ah_journal"})s.execute("CREATE "+t+"TABLE "+table+"(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),seller_name VARCHAR(16),price BIGINT,status VARCHAR(16),item LONGTEXT,amount INT,expires_at BIGINT,material VARCHAR(64))");
   s.execute("INSERT INTO accounts VALUES('buyer',1000),('seller',0),('poor',0)");
   for(String table:new String[]{"shop_journal","ah_journal"})s.execute("INSERT INTO "+table+" VALUES('j','seller','Seller',100,'OPEN','item',10,1000,'IRON_INGOT')");
  }
  c.setAutoCommit(false);
  assertEquals(0,DiscordRevenue.remaining(c,"seller","day",150000));
  for(String table:new String[]{"shop_journal","ah_journal"}) {
   assertNotNull(PartialPurchase.buy(c,table,"j","buyer",table+"price",99,"item",10,2,"rest",1,500));
   assertNotNull(PartialPurchase.buy(c,table,"j","poor",table+"poor",100,"item",10,2,"rest",1,500));
   assertNull(PartialPurchase.buy(c,table,"j","buyer",table+"one",100,"item",10,4,"rest",1,500)); c.commit();
   assertNotNull(PartialPurchase.buy(c,table,"j","buyer",table+"one",100,"item",10,4,"rest",1,500));
   assertEquals(4,value(c,"SELECT amount FROM pending_deliveries WHERE id='"+table+"one'"));
   assertEquals(6,value(c,"SELECT amount FROM "+table+" WHERE id='j' AND status='OPEN'"));
   assertNotNull(PartialPurchase.buy(c,table,"j","buyer",table+"stale",100,"item",10,1,"rest",1,500));
   assertNull(PartialPurchase.buy(c,table,"j","buyer",table+"two",60,"rest",6,6,"rest",1,500));c.commit();
   assertEquals(1,value(c,"SELECT COUNT(*) FROM "+table+" WHERE status='SOLD' AND amount=0"));
   assertNotNull(PartialPurchase.buy(c,table,"j","buyer",table+"two",60,"rest",6,6,"rest",1,500));
  }
  assertEquals(800,value(c,"SELECT balance FROM accounts WHERE uuid='buyer'"));
  assertEquals(195,value(c,"SELECT balance FROM accounts WHERE uuid='seller'"));
  assertEquals(5,value(c,"SELECT SUM(fee) FROM ah_fee_journal"));
  assertEquals(4,value(c,"SELECT COUNT(*) FROM pending_deliveries"));
  assertEquals(4,value(c,"SELECT COUNT(*) FROM sale_notices WHERE reported=FALSE"));
  java.util.List<SaleNoticeStore.Summary> notices=new java.util.ArrayList<>();
  SaleNoticeStore.deliver(c,"seller",notices::add); c.commit();
  assertEquals(java.util.List.of(new SaleNoticeStore.Summary("IRON_INGOT",20,195)),notices);
  SaleNoticeStore.deliver(c,"seller",notices::add); assertEquals(1,notices.size());
  assertEquals(4,value(c,"SELECT COUNT(*) FROM sale_notices WHERE reported=TRUE"));
  SaleNoticeStore.record(c,"online","seller","GOLD_INGOT",3,75,2);
  SaleNoticeStore.deliver(c,"seller",notices::add); c.commit();
  assertEquals(new SaleNoticeStore.Summary("GOLD_INGOT",3,75),notices.get(1));
  assertEquals(1,value(c,"SELECT COUNT(*) FROM sale_notices WHERE id='online' AND reported=TRUE"));
  SaleNoticeStore.deliver(c,"seller",notices::add); assertEquals(2,notices.size());
 }
 long value(Connection c,String sql)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
 @Test void h2()throws Exception{try(Connection c=DriverManager.getConnection("jdbc:h2:mem:partial;MODE=MySQL;DB_CLOSE_DELAY=-1")){exercise(c,false);}}
 @Test void mariaDb()throws Exception{String password=System.getenv("CRAFTCORE_DB_PASSWORD");assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password)){exercise(c,true);}}
}
