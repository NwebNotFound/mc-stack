package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class EconomyEndToEndTest {
  static final String A="00000000-0000-0000-0000-000000000001", B="00000000-0000-0000-0000-000000000002", C="00000000-0000-0000-0000-000000000003", D="00000000-0000-0000-0000-000000000004";
  static final String AH="00000000-0000-0000-0000-000000000005";
  static long value(Connection c,String sql)throws SQLException {try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
  static void check(Connection c,long a,long b,long d,long e,long total)throws SQLException {
    String[] ids={A,B,C,D}; long[] balances={a,b,d,e};
    for(int i=0;i<ids.length;i++) assertEquals(balances[i],value(c,"SELECT balance FROM accounts WHERE uuid='"+ids[i]+"'"),ids[i]);
    assertEquals(total,value(c,"SELECT SUM(balance) FROM accounts")+value(c,"SELECT COALESCE(SUM(remaining*unit_price),0) FROM buy_orders WHERE status='OPEN'"));
  }
  static void delivery(Connection c,String player,String source,int amount)throws SQLException {
    assertEquals(1,value(c,"SELECT COUNT(*) FROM pending_deliveries WHERE player_uuid='"+player+"' AND source='"+source+"' AND amount="+amount+" AND status='offen'"));
  }
  void exercise(Connection c,boolean temporary)throws Exception {
    String t=temporary?"TEMPORARY ":"";
    try(Statement s=c.createStatement()) {
      s.execute("CREATE "+t+"TABLE ah_fee_journal(purchase_id VARCHAR(36) PRIMARY KEY,listing_id VARCHAR(36),buyer VARCHAR(36),seller VARCHAR(36),gross BIGINT,fee BIGINT,created_at BIGINT)");
      s.execute("CREATE "+t+"TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
      s.execute("CREATE "+t+"TABLE purchase_ids(id VARCHAR(36) PRIMARY KEY)");
      s.execute("CREATE "+t+"TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt LONGTEXT,amount INT,source VARCHAR(32),status VARCHAR(16))");
      s.execute("CREATE "+t+"TABLE sale_notices(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),item VARCHAR(64),amount INT,cp BIGINT,sold_at BIGINT,reported BOOLEAN)");
      s.execute("CREATE "+t+"TABLE market_events(item VARCHAR(64),qty INT,unit_price DECIMAL(20,4),total_cp DECIMAL(20,2),source VARCHAR(8),ts BIGINT)");
      s.execute("CREATE "+t+"TABLE shop_events(id VARCHAR(36) PRIMARY KEY,listing_id VARCHAR(36),event_type VARCHAR(24),seller VARCHAR(36),seller_name VARCHAR(16),price BIGINT,actor VARCHAR(36),created_at TIMESTAMP)");
      for(String table:new String[]{"shop_journal","ah_journal"}) s.execute("CREATE "+t+"TABLE "+table+"(id VARCHAR(36) PRIMARY KEY,seller VARCHAR(36),seller_name VARCHAR(16),price BIGINT,status VARCHAR(16),item LONGTEXT,amount INT,expires_at BIGINT,material VARCHAR(64))");
      s.execute("CREATE "+t+"TABLE rcon_purchases(idem_key VARBINARY(64) PRIMARY KEY,kind VARCHAR(8),buyer VARCHAR(36),listing_id VARCHAR(36),qty INT,max_price BIGINT,response VARCHAR(80),created_at BIGINT)");
      s.execute("CREATE "+t+"TABLE buy_orders(id VARCHAR(36) PRIMARY KEY,buyer VARCHAR(36),buyer_name VARCHAR(16),item LONGTEXT,remaining INT,unit_price BIGINT,status VARCHAR(16))");
      s.execute("CREATE "+t+"TABLE buy_order_sales(id VARCHAR(36) PRIMARY KEY,order_id VARCHAR(36),seller VARCHAR(36),amount INT)");
      s.execute("CREATE "+t+"TABLE bank_revenue_daily(player VARCHAR(36),day_key VARCHAR(10),cents BIGINT,PRIMARY KEY(player,day_key))");
      s.execute("CREATE "+t+"TABLE bank_journal(id VARCHAR(36) PRIMARY KEY,player VARCHAR(36),item VARCHAR(64),cp BIGINT,status VARCHAR(24))");
      s.execute("INSERT INTO accounts VALUES('"+A+"',100000),('"+B+"',20000),('"+C+"',10000),('"+D+"',0)");
      s.execute("INSERT INTO shop_journal VALUES('full','"+B+"','B',1000,'OPEN','iron',10,1000,'IRON_INGOT'),('partial','"+B+"','B',2000,'OPEN','iron',10,1000,'IRON_INGOT')");
      s.execute("INSERT INTO ah_journal VALUES('"+AH+"','"+B+"','B',3000,'OPEN','iron',10,1000,'IRON_INGOT')");
    }
    c.setAutoCommit(false); check(c,100000,20000,10000,0,130000);
    assertNull(PartialPurchase.buy(c,"shop_journal","full",A,"p1",1000,"iron",10,10,"iron",1,500)); c.commit();
    check(c,99000,21000,10000,0,130000); delivery(c,A,"shop_journal",10);
    assertNull(PartialPurchase.buy(c,"shop_journal","partial",C,"p2",2000,"iron",10,4,"iron6",1,500)); c.commit();
    check(c,99000,21800,9200,0,130000); delivery(c,C,"shop_journal",4);
    assertEquals(6,value(c,"SELECT amount FROM shop_journal WHERE id='partial' AND status='OPEN'")); assertEquals(1200,value(c,"SELECT price FROM shop_journal WHERE id='partial'"));
    var request=new RconPurchase.Request("ah",A,AH,3,900,"e2e-ah");
    assertEquals("OK 3 900 7",RconPurchase.buy(c,request,1,(item,amount)->"iron7",500)); c.commit();
    check(c,98100,22655,9200,0,129955); delivery(c,A,"ah_journal",3);
    assertEquals(7,value(c,"SELECT amount FROM ah_journal WHERE id='"+AH+"' AND status='OPEN'")); assertEquals(2100,value(c,"SELECT price FROM ah_journal WHERE id='"+AH+"'"));
    assertEquals("OK 3 900 7",RconPurchase.buy(c,request,1,(item,amount)->fail("duplicate encoded"),500)); c.commit(); check(c,98100,22655,9200,0,129955);
    assertTrue(BuyOrderStore.create(c,"order",A,"A","stone",10,500)); c.commit(); check(c,93100,22655,9200,0,129955);
    assertTrue(BuyOrderStore.sell(c,"sale","order",D,4,"stone","stone4")); c.commit(); check(c,93100,22655,9200,2000,129955); delivery(c,A,"buy_order",4);
    assertEquals(6,value(c,"SELECT remaining FROM buy_orders WHERE id='order' AND status='OPEN'"));
    assertFalse(BuyOrderStore.cancel(c,"order",A)); c.commit(); check(c,93100,22655,9200,2000,129955);
    assertEquals(6,value(c,"SELECT remaining FROM buy_orders WHERE id='order' AND status='OPEN'"));
    assertEquals(3,value(c,"SELECT COUNT(*) FROM sale_notices WHERE seller='"+B+"' AND reported=FALSE"));
    List<SaleNoticeStore.Summary> notices=new ArrayList<>(); SaleNoticeStore.deliver(c,B,notices::add); c.commit();
    assertEquals(List.of(new SaleNoticeStore.Summary("IRON_INGOT",17,2655)),notices); check(c,93100,22655,9200,2000,129955);
    SaleNoticeStore.deliver(c,B,notices::add); c.commit(); assertEquals(1,notices.size()); assertEquals(3,value(c,"SELECT COUNT(*) FROM sale_notices WHERE reported=TRUE")); check(c,93100,22655,9200,2000,129955);
    assertTrue(EconomyStore.transfer(c,A,D,10000)); c.commit(); check(c,83100,22655,9200,12000,129955);
    assertFalse(EconomyStore.transfer(c,C,D,9201)); c.commit(); check(c,83100,22655,9200,12000,129955);
    bank(c,D,"bank1",100000,true); check(c,83100,22655,9200,112000,229955);
    bank(c,D,"over",50001,false); check(c,83100,22655,9200,112000,229955);
    bank(c,D,"bank2",50000,true); check(c,83100,22655,9200,162000,279955);
    bank(c,D,"over2",1,false); check(c,83100,22655,9200,162000,279955);
    assertEquals(150000,value(c,"SELECT cents FROM bank_revenue_daily WHERE player='"+D+"' AND day_key='2026-10-29'"));
    assertEquals(0,DailyRevenue.remaining(c,D,"2026-10-29",150000));
    assertEquals(4,value(c,"SELECT COUNT(*) FROM pending_deliveries WHERE status='offen'"));
  }
  static void bank(Connection c,String player,String id,long cents,boolean expected)throws Exception {
    DailyRevenue.lock(c,player,"2026-10-29"); boolean accepted=DailyRevenue.reserve(c,player,"2026-10-29",cents,150000); assertEquals(expected,accepted);
    if(accepted) {try(PreparedStatement s=c.prepareStatement("INSERT INTO bank_journal VALUES(?,?,'STONE',?,'PENDING')")){s.setString(1,id);s.setString(2,player);s.setLong(3,cents);s.executeUpdate();} assertTrue(BankJournal.finishSale(c,id,material->assertEquals("STONE",material)));}
    c.commit();
  }
  @Test void h2()throws Exception {try(Connection c=DriverManager.getConnection("jdbc:h2:mem:e2e_"+UUID.randomUUID()+";MODE=MySQL")){exercise(c,false);}}
  @Test void mariaDb()throws Exception {String password=System.getenv("CRAFTCORE_DB_PASSWORD"); assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST"))); try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password)){exercise(c,true);}}
}
