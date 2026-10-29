package de.mcstack.craftcore;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankBuyLimitTest {
  @Test void limitsEachOwnerItemAndDayAndRollsBack() throws Exception {
    try(Connection c=DriverManager.getConnection("jdbc:h2:mem:buy_limit;MODE=MySQL")) {
      c.createStatement().execute("CREATE TABLE bank_buy_daily(owner VARCHAR(40),item VARCHAR(64),day_key VARCHAR(10),amount INT NOT NULL,PRIMARY KEY(owner,item,day_key))");
      c.setAutoCommit(false);
      assertTrue(BankBuyLimit.reserve(c,"d:123","IRON_INGOT","day",2));
      assertTrue(BankBuyLimit.reserve(c,"d:123","IRON_INGOT","day",2));
      assertFalse(BankBuyLimit.reserve(c,"d:123","IRON_INGOT","day",2));
      assertTrue(BankBuyLimit.reserve(c,"d:123","GOLD_INGOT","day",2));
      assertTrue(BankBuyLimit.reserve(c,"u:other","IRON_INGOT","day",2));
      assertTrue(BankBuyLimit.reserve(c,"d:123","IRON_INGOT","next",2));
      c.rollback();
      assertTrue(BankBuyLimit.reserve(c,"d:123","IRON_INGOT","day",2));
    }
  }
}
