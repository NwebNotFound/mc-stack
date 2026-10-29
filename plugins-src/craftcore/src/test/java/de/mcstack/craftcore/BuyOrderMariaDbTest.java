package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
class BuyOrderMariaDbTest {
 @Test void offlinePartialDuplicateCancelAndInsufficient()throws Exception {
  String password=System.getenv("CRAFTCORE_DB_PASSWORD");assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
  try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password);Statement s=c.createStatement()){
   s.execute("CREATE TEMPORARY TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
   s.execute("CREATE TEMPORARY TABLE buy_orders(id VARCHAR(36) PRIMARY KEY,buyer VARCHAR(36),buyer_name VARCHAR(16),item LONGTEXT,remaining INT,unit_price BIGINT,status VARCHAR(16))");
   s.execute("CREATE TEMPORARY TABLE buy_order_sales(id VARCHAR(36) PRIMARY KEY,order_id VARCHAR(36),seller VARCHAR(36),amount INT)");
   s.execute("CREATE TEMPORARY TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt LONGTEXT,amount INT,source VARCHAR(32),status VARCHAR(16))");
   s.execute("INSERT INTO accounts VALUES('buyer',1000),('seller',0)");c.setAutoCommit(false);
   assertFalse(BuyOrderStore.create(c,"poor","buyer","Buyer","stone",11,100));
   assertTrue(BuyOrderStore.create(c,"order","buyer","Buyer","stone",10,50));
   assertTrue(BuyOrderStore.sell(c,"sale","order","seller",3,"stone","three"));
   assertFalse(BuyOrderStore.sell(c,"sale","order","seller",3,"stone","three"));
   assertEquals(150,BuyOrderStoreTest.val(c,"SELECT balance FROM accounts WHERE uuid='seller'"));
   assertEquals(3,BuyOrderStoreTest.val(c,"SELECT amount FROM pending_deliveries WHERE player_uuid='buyer'"));
   assertTrue(BuyOrderStore.cancel(c,"order","buyer"));assertFalse(BuyOrderStore.cancel(c,"order","buyer"));
   assertEquals(850,BuyOrderStoreTest.val(c,"SELECT balance FROM accounts WHERE uuid='buyer'"));c.rollback();
  }
 }
}
