package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CurvePriceTest {
  static final CurvePrice.Factors F = new CurvePrice.Factors(1.5, 0.75, 0.5, 72, 12);

  @Test void diamondMovesAQuarterPercent() {
    assertEquals(15000, CurvePrice.sellCents(CurvePrice.mid(200, 0), null, F));
    assertEquals(30000, CurvePrice.buyCents(CurvePrice.mid(200, 0), F));
    assertEquals(199.500624, CurvePrice.mid(200, -1), 1e-4);
    assertEquals(14962, CurvePrice.sellCents(CurvePrice.mid(200, -1), null, F));
    assertEquals(29925, CurvePrice.buyCents(CurvePrice.mid(200, -1), F));
    assertEquals(200.500626, CurvePrice.mid(200, 1), 1e-4);
    assertEquals(30075, CurvePrice.buyCents(CurvePrice.mid(200, 1), F));
  }

  @Test void quoteAfterVolume() {
    assertQuote("COBBLESTONE", 0.05, 128, -1, 0.049611, 7, 3);
    assertQuote("COBBLESTONE", 0.05, 128, -64, 0.030327, 4, 2);
    assertQuote("COBBLESTONE", 0.05, 128, -640, 0.0125, 2, 1);
    assertQuote("COBBLESTONE", 0.05, 128, -10000, 0.0125, 2, 1);
    assertQuote("SAND", 0.10, 128, -1, 0.099222, 14, 7);
    assertQuote("SAND", 0.10, 128, -64, 0.060653, 9, 4);
    assertQuote("DIAMOND", 200, 400, -1, 199.500624, 29925, 14962);
    assertQuote("DIAMOND", 200, 400, -64, 170.428758, 25564, 12782);
    assertQuote("DIAMOND", 200, 400, -640, 50, 7500, 3750);
    assertQuote("NETHERITE_INGOT", 4000, 400, -64, 3408.575156, 511286, 255643);
    assertQuote("NETHERITE_INGOT", 4000, 400, 64, 4694.043484, 704106, 352053);
    assertEquals(128, CurvePrice.depth(0.05));
    assertEquals(400, CurvePrice.depth(200));
    assertEquals(400, CurvePrice.depth(4000));
  }

  @Test void netheriteIngotSecondUnitAndStack() {
    assertEquals(300000, CurvePrice.sellCents(CurvePrice.mid(4000, 0), null, F));
    assertEquals(599250, CurvePrice.sumSell(4000, 0, 2, null, F));
    assertEquals(17764901, CurvePrice.sumSell(4000, 0, 64, null, F));
    long singles = 0;
    for (int i = 0; i < 64; i++) singles += CurvePrice.sellCents(CurvePrice.mid(4000, -i), null, F);
    assertEquals(singles, CurvePrice.sumSell(4000, 0, 64, null, F));
  }

  @Test void quantityCapAndRecovery() {
    assertThrows(IllegalArgumentException.class, () -> CurvePrice.sumSell(0.05, 0, 2305, null, F));
    assertEquals(2304, CurvePrice.MAX_QTY);
    assertEquals(10110, dump(0.05, 10000));
    assertEquals(10172, dumpFromCeiling(0.05));
    assertEquals(10459, dump(0.10, 10000));
    assertEquals(10616, dumpFromCeiling(0.10));
    assertTrue(dump(0.05, 10000) < 180000);
    assertTrue(dump(0.10, 10000) < 990004);
    assertEquals(798523467L, dump(4000, 10000));
    double floorNet = -400 * Math.log(4);
    assertEquals(100, CurvePrice.mid(200, CurvePrice.fold(floorNet, 200, 72L * 3_600_000, F)), 1e-6);
    assertEquals(7500, CurvePrice.sellCents(CurvePrice.mid(200, CurvePrice.fold(floorNet, 200, 72L * 3_600_000, F)), null, F));
    assertEquals(15000, CurvePrice.buyCents(CurvePrice.mid(200, CurvePrice.fold(floorNet, 200, 72L * 3_600_000, F)), F));
    double cobbleFloor = -128 * Math.log(4);
    assertEquals(0.025, CurvePrice.mid(0.05, CurvePrice.fold(cobbleFloor, 0.05, 12L * 3_600_000, F)), 1e-9);
    assertEquals(-128, CurvePrice.fold(-128, 0.05, -1, F));
    assertEquals(0.018394, CurvePrice.mid(0.05, -128), 1e-6);
    assertEquals(1, CurvePrice.sellCents(CurvePrice.mid(0.05, -128), null, F));
    assertEquals(0.030327, CurvePrice.mid(0.05, CurvePrice.fold(-128, 0.05, 12L * 3_600_000, F)), 1e-6);
    assertEquals(2, CurvePrice.sellCents(CurvePrice.mid(0.05, CurvePrice.fold(-128, 0.05, 12L * 3_600_000, F)), null, F));
  }

  @Test void roundTripIsALoss() {
    assertTrue(CurvePrice.sumSell(200, 0, 1, null, F) < CurvePrice.sumBuy(200, -1, 1, F));
    assertTrue(CurvePrice.sumSell(200, 0, 64, null, F) < buyBack(200, 64));
    assertTrue(CurvePrice.sumSell(4000, 0, 64, null, F) < buyBack(4000, 64));
    assertTrue(dump(0.05, 64) < buyBack(0.05, 64));
  }

  @Test void revenueLimitReadsConfig() {
    var cfg = new YamlConfiguration();
    assertEquals(150000, Market.revenueLimit(cfg));
    cfg.set("market.daily-revenue-limit", 20.0);
    assertEquals(2000, Market.revenueLimit(cfg));
    cfg.set("market.daily-revenue-limit", 0);
    assertEquals(150000, Market.revenueLimit(cfg));
    assertEquals(150000, Market.revenueLimit(RecipeArbitrageTest.liveCfg()));
  }

  @Test void firstRowUsesInsertIgnore() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:curve_" + UUID.randomUUID() + ";MODE=MySQL"); Statement s = c.createStatement()) {
      CurvePrice.migrate(c);
      c.setAutoCommit(false);
      CurvePrice.lock(c, "STONE", 1000);
      CurvePrice.lock(c, "STONE", 2000);
      try (ResultSet r = s.executeQuery("SELECT COUNT(*), net FROM market_curve WHERE item='STONE'")) {
        assertTrue(r.next());
        assertEquals(1, r.getInt(1));
        assertEquals(0, r.getDouble(2));
      }
    }
  }

  private static void assertQuote(String name, double base, double depth, double net, double mid, long buy, long sell) {
    assertEquals(depth, CurvePrice.depth(base), name);
    assertEquals(mid, CurvePrice.mid(base, net), 1e-4, name);
    CurvePrice.Cent cent = CurvePrice.cents(base, net, null, F);
    assertEquals(buy, cent.buy(), name);
    assertEquals(sell, cent.sell(), name);
  }

  private static long dump(double base, int qty) {
    long total = 0;
    int left = qty;
    double net = 0;
    while (left > 0) {
      int step = Math.min(CurvePrice.MAX_QTY, left);
      total += CurvePrice.sumSell(base, net, step, null, F);
      net -= step;
      left -= step;
    }
    return total;
  }

  private static long dumpFromCeiling(double base) {
    double net = CurvePrice.depth(base) * Math.log(1.2);
    long total = 0;
    int left = 10000;
    while (left > 0) {
      int step = Math.min(CurvePrice.MAX_QTY, left);
      total += CurvePrice.sumSell(base, net, step, null, F);
      net -= step;
      left -= step;
    }
    return total;
  }

  private static long buyBack(double base, int qty) {
    long total = 0;
    int left = qty;
    double net = -qty;
    while (left > 0) {
      int step = Math.min(CurvePrice.MAX_QTY, left);
      total += CurvePrice.sumBuy(base, net, step, F);
      net += step;
      left -= step;
    }
    return total;
  }
}
