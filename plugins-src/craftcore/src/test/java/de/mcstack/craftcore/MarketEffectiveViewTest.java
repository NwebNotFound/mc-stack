package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketEffectiveViewTest {
  record Row(Double buy, Double sell, double base, boolean buyEnabled, boolean sellEnabled) {}
  static Row row(Connection connection, String item) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("SELECT buy_price,sell_price,base_price,buy_enabled,sell_enabled FROM market_effective WHERE item=?")) {
      query.setString(1, item);
      try (ResultSet result = query.executeQuery()) {
        assertTrue(result.next(), item);
        Double buy = result.getObject(1) == null ? null : result.getDouble(1);
        Double sell = result.getObject(2) == null ? null : result.getDouble(2);
        return new Row(buy, sell, result.getDouble(3), result.getBoolean(4), result.getBoolean(5));
      }
    }
  }
  @Test void viewAppliesHalfBuyRecipeDiscountOreRuleAndBothLockSources() throws Exception {
    var cfg = new YamlConfiguration();
    cfg.set("base.WHEAT", 0.5);
    cfg.set("base.IRON_INGOT", 8.0);
    cfg.set("base.DIAMOND", 100.0);
    cfg.set("base.TOTEM_OF_UNDYING", 533.0);
    cfg.set("market.lock-fallback.WHEAT.sell", true);
    cfg.set("market.lock-fallback.IRON_INGOT.buy", true);
    var recipes = List.of(
      MarketEffectiveTest.recipe(Material.BREAD, 1, Material.WHEAT, 3),
      MarketEffectiveTest.recipe(Material.IRON_INGOT, 1, Material.IRON_ORE, 1),
      MarketEffectiveTest.recipe(Material.IRON_INGOT, 1, Material.RAW_IRON, 1),
      MarketEffectiveTest.recipe(Material.DIAMOND_BLOCK, 1, Material.DIAMOND, 9));
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:market_effective_" + UUID.randomUUID() + ";MODE=MySQL")) {
      MarketEffective.refresh(connection, cfg, MarketEffective.bases(cfg), recipes);
      try (Statement statement = connection.createStatement()) {
        statement.execute("INSERT INTO market_locks(item,lock_buy,lock_sell) VALUES('BREAD',FALSE,FALSE)");
        statement.execute("INSERT INTO market_locks(item,lock_buy,lock_sell) VALUES('DIAMOND',TRUE,FALSE)");
        statement.execute("INSERT INTO market_locks(item,lock_buy,lock_sell) VALUES('DIAMOND_BLOCK',FALSE,TRUE)");
      }
      Row wheat = row(connection, "WHEAT");
      assertEquals(0.5, wheat.buy(), 1e-9);
      assertNull(wheat.sell());
      assertTrue(wheat.buyEnabled());
      assertFalse(wheat.sellEnabled());
      Row bread = row(connection, "BREAD");
      assertEquals(1.35, bread.buy(), 1e-9);
      assertEquals(0.675, bread.sell(), 1e-9);
      assertTrue(bread.buyEnabled());
      assertTrue(bread.sellEnabled());
      Row ore = row(connection, "IRON_ORE");
      assertNull(ore.buy());
      assertEquals(3.6, ore.sell(), 1e-9);
      assertFalse(ore.buyEnabled());
      assertTrue(ore.sellEnabled());
      assertEquals(ore, row(connection, "RAW_IRON"));
      Row ingot = row(connection, "IRON_INGOT");
      assertNull(ingot.buy());
      assertEquals(4.0, ingot.sell(), 1e-9);
      assertFalse(ingot.buyEnabled());
      assertTrue(ingot.sellEnabled());
      Row diamond = row(connection, "DIAMOND");
      assertNull(diamond.buy());
      assertEquals(50.0, diamond.sell(), 1e-9);
      assertFalse(diamond.buyEnabled());
      Row block = row(connection, "DIAMOND_BLOCK");
      assertNull(block.buy());
      assertNull(block.sell());
      assertFalse(block.buyEnabled());
      assertFalse(block.sellEnabled());
      Row totem = row(connection, "TOTEM_OF_UNDYING");
      assertNull(totem.buy());
      assertEquals(266.5, totem.sell(), 1e-9);
      assertFalse(totem.buyEnabled());
      assertTrue(totem.sellEnabled());
      cfg.set("market.lock-fallback.BREAD.buy", true);
      MarketEffective.refresh(connection, cfg, MarketEffective.bases(cfg), recipes);
      Row lockedBread = row(connection, "BREAD");
      assertNull(lockedBread.buy());
      assertFalse(lockedBread.buyEnabled());
      assertEquals(0.675, lockedBread.sell(), 1e-9);
    }
  }
}
