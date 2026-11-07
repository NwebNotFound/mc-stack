package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuyOrderStoreTest {
 static Connection setup(String name)throws Exception {
  Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL;DB_CLOSE_DELAY=-1");
  try(Statement s=c.createStatement()){
   s.execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
   s.execute("CREATE TABLE buy_orders(id VARCHAR(36) PRIMARY KEY,buyer VARCHAR(36),buyer_name VARCHAR(16),item LONGTEXT,remaining INT,unit_price BIGINT,status VARCHAR(16))");
   s.execute("CREATE TABLE buy_order_sales(id VARCHAR(36) PRIMARY KEY,order_id VARCHAR(36),seller VARCHAR(36),amount INT)");
   s.execute("CREATE TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt LONGTEXT,amount INT,source VARCHAR(32),status VARCHAR(16))");
   s.execute("INSERT INTO accounts VALUES('buyer',1000),('seller',0)");
  } c.setAutoCommit(false);return c;
 }
 static long val(Connection c,String sql)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
 @Test void reservePartialRepeatCancel()throws Exception {try(Connection c=setup("orders")){
  assertTrue(BuyOrderStore.create(c,"order","buyer","Buyer","stone",10,50));
  assertEquals(500,val(c,"SELECT balance FROM accounts WHERE uuid='buyer'"));
  assertTrue(BuyOrderStore.sell(c,"sale","order","seller",3,"stone","three"));
  assertFalse(BuyOrderStore.sell(c,"sale","order","seller",3,"stone","three"));
  assertEquals(150,val(c,"SELECT balance FROM accounts WHERE uuid='seller'"));
  assertEquals(3,val(c,"SELECT amount FROM pending_deliveries WHERE player_uuid='buyer'"));
  assertTrue(BuyOrderStore.cancel(c,"order","buyer"));
  assertFalse(BuyOrderStore.cancel(c,"order","buyer"));
  assertEquals(850,val(c,"SELECT balance FROM accounts WHERE uuid='buyer'"));
  assertFalse(BuyOrderStore.sell(c,"late","order","seller",1,"stone","one"));c.commit();
 }}
 @Test void cancelUntouchedOrderOnlyOnce()throws Exception {try(Connection c=setup("orders_cancel")){
  assertTrue(BuyOrderStore.create(c,"order","buyer","Buyer","stone",10,50));
  assertFalse(BuyOrderStore.cancel(c,"order","seller"));
  assertTrue(BuyOrderStore.cancel(c,"order","buyer"));
  assertFalse(BuyOrderStore.cancel(c,"order","buyer"));
  assertEquals(1000,val(c,"SELECT balance FROM accounts WHERE uuid='buyer'"));c.commit();
 }}
 @Test void insufficientAndRollback()throws Exception {try(Connection c=setup("orders2")){
  assertFalse(BuyOrderStore.create(c,"bad","buyer","Buyer","stone",11,100));
  assertEquals(0,val(c,"SELECT COUNT(*) FROM buy_orders"));
  assertTrue(BuyOrderStore.create(c,"ok","buyer","Buyer","stone",5,100));c.rollback();
  assertEquals(1000,val(c,"SELECT balance FROM accounts WHERE uuid='buyer'"));
 }}
}
