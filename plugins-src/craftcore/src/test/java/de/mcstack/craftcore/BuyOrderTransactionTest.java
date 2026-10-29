package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuyOrderTransactionTest {
 @Test void insufficientInventoryRollsBackSale() throws Exception {try(Connection c=BuyOrderStoreTest.setup("inventory_rollback")){
  assertTrue(BuyOrderStore.create(c,"order","buyer","Buyer","stone",10,50));c.commit();
  assertThrows(SQLException.class,()->BuyOrderTransaction.sell(c,"sale","order","seller",3,"stone",()->false));c.rollback();
  assertEquals(10,BuyOrderStoreTest.val(c,"SELECT remaining FROM buy_orders WHERE id='order'"));
  assertEquals(0,BuyOrderStoreTest.val(c,"SELECT balance FROM accounts WHERE uuid='seller'"));
  assertEquals(0,BuyOrderStoreTest.val(c,"SELECT COUNT(*) FROM pending_deliveries"));
  assertTrue(BuyOrderTransaction.sell(c,"sale2","order","seller",3,"stone",()->true));c.commit();
  assertEquals(7,BuyOrderStoreTest.val(c,"SELECT remaining FROM buy_orders WHERE id='order'"));
  assertTrue(BuyOrderStore.cancel(c,"order","buyer"));c.commit();
  assertEquals(850,BuyOrderStoreTest.val(c,"SELECT balance FROM accounts WHERE uuid='buyer'"));
 }}
}
