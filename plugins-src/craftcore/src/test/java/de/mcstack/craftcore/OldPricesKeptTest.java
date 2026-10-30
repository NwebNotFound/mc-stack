package de.mcstack.craftcore;

import java.sql.*;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Neue Preise gelten nur fuer neue Angebote: laufende Auktionen und offene Kaufauftraege behalten ihren Preis. */
class OldPricesKeptTest {
  static void reprice(Connection c, double ironBase) throws SQLException {
    var cfg = new YamlConfiguration(); cfg.set("base.IRON_INGOT", ironBase); cfg.set("base.STONE", ironBase / 40);
    MarketEffective.refresh(c, cfg, MarketEffective.bases(cfg), List.of());
  }
  @Test void openBuyOrderPaysItsOriginalUnitPriceAfterRepricing() throws Exception {
    try (Connection c = BuyOrderStoreTest.setup("old_orders")) {
      reprice(c, 8);
      assertTrue(BuyOrderStore.create(c, "order", "buyer", "Buyer", "stone", 10, 50));
      reprice(c, 12);
      assertTrue(BuyOrderStore.sell(c, "sale", "order", "seller", 2, "stone", "x"));
      assertEquals(100, BuyOrderStoreTest.val(c, "SELECT balance FROM accounts WHERE uuid='seller'"));
      assertEquals(50, BuyOrderStoreTest.val(c, "SELECT unit_price FROM buy_orders WHERE id='order'"));
    }
  }
  @Test void runningAuctionKeepsItsPriceAfterRepricing() throws Exception {
    try (Connection c = new AuctionStoreTest().setup("old_ah")) {
      reprice(c, 8); reprice(c, 12);
      assertNull(AuctionStore.buy(c, "j", "buyer", 99, 500, 1000L, "item"));
      assertEquals(9000, new AuctionStoreTest().value(c, "SELECT balance FROM accounts WHERE uuid='buyer'"));
    }
  }
}
