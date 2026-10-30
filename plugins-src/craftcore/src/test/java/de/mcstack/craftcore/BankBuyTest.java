package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankBuyTest {
  static final String U="11111111-1111-1111-1111-111111111111";
  /** Fake env on H2: accounts + bank_buy_daily + pending_deliveries, price 100 ct rising 10 ct per unit. */
  static final class Fake implements BankBuy.Env {
    final Map<String,Long> price=new HashMap<>(Map.of("IRON_INGOT",100L,"DIRT",5L));
    final Set<String> locked=new HashSet<>(); int limit=64;
    public String blocked(Connection c,String item){ return !price.containsKey(item)?"UNKNOWN":locked.contains(item)?"LOCKED":null; }
    public int maxStack(String item){ return 64; }
    public long unitPrice(Connection c,String item){ return price.get(item); }
    public void moved(Connection c,String item){ price.merge(item,10L,Long::sum); }
    public boolean reserve(Connection c,String u,String item)throws Exception{ return BankBuyLimit.reserve(c,"u:"+u,item,"day",limit); }
    public long balance(Connection c,String u)throws Exception{ try(ResultSet r=c.createStatement().executeQuery("SELECT balance FROM accounts WHERE uuid='"+u+"'")){ return r.next()?r.getLong(1):0; } }
    public void debit(Connection c,String u,long cents)throws Exception{ try(PreparedStatement q=c.prepareStatement("UPDATE accounts SET balance=balance-? WHERE uuid=?")){q.setLong(1,cents);q.setString(2,u);q.executeUpdate();} }
    public void deliver(Connection c,String id,String u,String item,int amount)throws Exception{ try(PreparedStatement q=c.prepareStatement("INSERT INTO pending_deliveries(id,player_uuid,amount,source,status) VALUES(?,?,?,'bank','offen')")){q.setString(1,id);q.setString(2,u);q.setInt(3,amount);q.executeUpdate();} }
  }
  private Connection db(String name,long balance) throws SQLException {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL");
    try(Statement s=c.createStatement()) {
      s.execute("CREATE TABLE accounts(uuid CHAR(36) PRIMARY KEY, balance BIGINT NOT NULL)");
      s.execute("INSERT INTO accounts VALUES('"+U+"',"+balance+")");
      s.execute("CREATE TABLE bank_buy_daily(owner VARCHAR(40),item VARCHAR(64),day_key VARCHAR(10),amount INT NOT NULL,PRIMARY KEY(owner,item,day_key))");
      s.execute("CREATE TABLE pending_deliveries(id CHAR(36) PRIMARY KEY, player_uuid CHAR(36), amount INT, source VARCHAR(32), status VARCHAR(16))");
      s.execute("CREATE TABLE order_fulfillment (request_id VARCHAR(64) NOT NULL PRIMARY KEY, delivery_id CHAR(36) NULL, status VARCHAR(16) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }
    BankBuy.migrate(c); c.setAutoCommit(false); return c;
  }
  static String one(Connection c,String sql)throws SQLException{ try(ResultSet r=c.createStatement().executeQuery(sql)){ return r.next()?r.getString(1):null; } }

  @Test void parsesOnlyTheBankForm() {
    assertEquals(new BankBuy.Request(U,"IRON_INGOT",16,"web:a.b-1"), BankBuy.parse(new String[]{"buy","bank",U,"minecraft:iron_ingot","16","web:a.b-1"}));
    assertNull(BankBuy.parse(new String[]{"buy","bank",U,"iron_ingot","2305","web:a.b-1"}));
    assertNull(BankBuy.parse(new String[]{"buy","bank",U,"iron_ingot","1","short"}));
    assertNull(BankBuy.parse(new String[]{"buy",U,U,"1","100","k"}));
    assertNull(BankBuy.parse(new String[]{"buy","bank",U,"iron_ingot","0","k"}));
    assertNull(BankBuy.parse(new String[]{"buy","bank","AAAAAAAA-1111-1111-1111-111111111111","iron_ingot","1","k"}));
    assertNull(BankBuy.parse(new String[]{"buy","bank",U,"iron ingot","1","k"}));
    assertNull(BankBuy.parse(new String[]{"buy","bank",U,"iron_ingot","1","bad key!"}));
  }
  @Test void okChargesPriceAtExecutionOnceAndIsReadyForPickup() throws Exception {
    try(Connection c=db("bb_ok",10_000)) {
      Fake env=new Fake(); var req=new BankBuy.Request(U,"IRON_INGOT",3,"web-1");
      assertEquals("OK 3 330 0", BankBuy.execute(c,req,1,env)); // 100+110+120
      assertEquals("9670", one(c,"SELECT balance FROM accounts"));
      assertEquals("ready", one(c,"SELECT status FROM order_fulfillment WHERE request_id='web-1'"));
      assertEquals("3", one(c,"SELECT amount FROM pending_deliveries WHERE id=(SELECT delivery_id FROM order_fulfillment WHERE request_id='web-1')"));
      assertEquals("OK 3 330 0", BankBuy.execute(c,req,2,env), "same request_id: stored answer");
      assertEquals("9670", one(c,"SELECT balance FROM accounts"), "charged only once");
      assertEquals("1", one(c,"SELECT COUNT(*) FROM pending_deliveries"));
      assertEquals("ERR DUPLICATE", BankBuy.execute(c,new BankBuy.Request(U,"IRON_INGOT",4,"web-1"),3,env));
    }
  }
  @Test void largeOrderIsSplitIntoStacks() throws Exception {
    try(Connection c=db("bb_split",1_000_000)) {
      Fake env=new Fake(); env.limit=2304;
      assertTrue(BankBuy.execute(c,new BankBuy.Request(U,"DIRT",130,"web-split"),1,env).startsWith("OK 130 "));
      assertEquals("3", one(c,"SELECT COUNT(*) FROM pending_deliveries"));
      assertEquals("130", one(c,"SELECT SUM(amount) FROM pending_deliveries"));
      assertEquals("64", one(c,"SELECT amount FROM pending_deliveries WHERE id=(SELECT delivery_id FROM order_fulfillment WHERE request_id='web-split')"));
    }
  }
  @Test void noFundsFailsWithoutSideEffects() throws Exception {
    try(Connection c=db("bb_funds",200)) {
      Fake env=new Fake();
      assertEquals("ERR NO_FUNDS", BankBuy.execute(c,new BankBuy.Request(U,"IRON_INGOT",2,"web-2"),1,env));
      assertEquals("200", one(c,"SELECT balance FROM accounts"));
      assertEquals("0", one(c,"SELECT COUNT(*) FROM pending_deliveries"));
      assertNull(one(c,"SELECT amount FROM bank_buy_daily"), "limit reservation rolled back");
      assertEquals("failed", one(c,"SELECT status FROM order_fulfillment WHERE request_id='web-2'"));
      assertEquals("ERR NO_FUNDS", BankBuy.execute(c,new BankBuy.Request(U,"IRON_INGOT",2,"web-2"),2,env));
    }
  }
  @Test void lockUnknownLimitAndStack() throws Exception {
    try(Connection c=db("bb_checks",1_000_000)) {
      Fake env=new Fake(); env.locked.add("DIRT"); env.limit=5;
      assertEquals("ERR LOCKED", BankBuy.execute(c,new BankBuy.Request(U,"DIRT",1,"a"),1,env));
      assertEquals("ERR UNKNOWN", BankBuy.execute(c,new BankBuy.Request(U,"BEDROCK",1,"b"),1,env));
      assertEquals("ERR INVALID", BankBuy.execute(c,new BankBuy.Request(U,"IRON_INGOT",2305,"c"),1,env));
      assertEquals("ERR LIMIT", BankBuy.execute(c,new BankBuy.Request(U,"IRON_INGOT",6,"d"),1,env));
      assertTrue(BankBuy.execute(c,new BankBuy.Request(U,"IRON_INGOT",5,"e"),1,env).startsWith("OK 5 "));
      assertEquals("ERR LIMIT", BankBuy.execute(c,new BankBuy.Request(U,"IRON_INGOT",1,"f"),1,env));
      for(String id:List.of("a","b","c","d","f")) assertEquals("failed", one(c,"SELECT status FROM order_fulfillment WHERE request_id='"+id+"'"), id);
    }
  }
}
