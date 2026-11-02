package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScarcityTest {
  static final CurvePrice.Factors F = new CurvePrice.Factors(1.5, 0.75, 0.5, 72, 12);

  @Test void oneOfAKindRisesAndMassHeldFallsInsideBounds() {
    Scarcity.Settings settings = Scarcity.settings(RecipeArbitrageTest.liveCfg());
    assertEquals(0.5, settings.smin());
    assertEquals(3.0, settings.smax());
    assertEquals(8.0, settings.uniqueSmax());
    assertTrue(settings.unique().contains("DRAGON_EGG"));
    double egg = Scarcity.factor(8000, 1, settings, true);
    assertTrue(egg > 1, "dragon egg " + egg);
    assertTrue(egg <= settings.uniqueSmax(), "dragon egg " + egg);
    double common = Scarcity.factor(0.05, 20_000, settings, false);
    assertTrue(common < 1, "cobble " + common);
    assertTrue(common >= settings.smin(), "cobble " + common);
    assertEquals(1, MarketSupply.multiplier("UNSCANNED", 0.05, settings));
  }

  @Test void emaCannotJumpToTheNewSupply() {
    assertEquals(2500, Scarcity.ema(0, 10_000, 0.25));
    assertEquals(10_000, Scarcity.ema(-1, 10_000, 0.25));
    Scarcity.Settings settings = Scarcity.settings(RecipeArbitrageTest.liveCfg());
    double partial = Scarcity.factor(0.05, Scarcity.ema(100, 20_000, settings.emaAlpha()), settings, false);
    double settled = Scarcity.factor(0.05, 20_000, settings, false);
    assertTrue(partial > settled);
    assertTrue(partial <= settings.smax());
    assertTrue(settled >= settings.smin());
  }

  @Test void scarcityKeepsSellAtMostHalfTheBuy() {
    for (double scale : new double[] {0.5, 1, 3, 8}) {
      double mid = 12.5 * scale;
      long sell = CurvePrice.rawSell(mid, F);
      long buy = CurvePrice.buy0(mid, F);
      assertTrue(sell * 2L <= buy, scale + " sell " + sell + " buy " + buy);
      assertTrue(CurvePrice.sellCents(mid, null, F) < CurvePrice.buyCents(mid, F));
    }
  }

  @Test void scarcityCannotMakeARecipeProfitable() {
    var cfg = RecipeArbitrageTest.liveCfg();
    var prices = RecipeArbitrageTest.prices();
    double cobble = Market.quote(cfg, Material.COBBLESTONE, prices.getDouble("base.COBBLESTONE")).base();
    double stone = Market.quote(cfg, Material.STONE, prices.getDouble("base.STONE")).base();
    long cost = CurvePrice.buyCents(CurvePrice.lo(cobble) * 0.5, F);
    long sell = CurvePrice.sellCents(CurvePrice.hi(stone) * 3.0, cost, F);
    assertTrue(sell <= cost, sell + " > " + cost);
    assertEquals(150000, Market.revenueLimit(cfg));
  }

  @Test void tallyCountsInventoryAndEnderChestOnly() {
    Map<String, Long> counts = MarketSupply.tallyStacks(List.of(
      new MarketSupply.CountedStack("COBBLESTONE", 64),
      new MarketSupply.CountedStack("COBBLESTONE", 10),
      new MarketSupply.CountedStack("DRAGON_EGG", 1),
      new MarketSupply.CountedStack("AIR", 1)));
    assertEquals(74L, counts.get("COBBLESTONE"));
    assertEquals(1L, counts.get("DRAGON_EGG"));
    assertFalse(counts.containsKey("AIR"));
  }

  @Test void supplyRowsSmoothAndIncludeMarketStock() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:supply_" + UUID.randomUUID() + ";MODE=MySQL"); Statement s = c.createStatement()) {
      MarketSupply.migrate(c);
      c.setAutoCommit(false);
      MarketSupply.replaceHolder(c, "player:a", Map.of("COBBLESTONE", 64L, "DRAGON_EGG", 1L));
      MarketSupply.replaceHolder(c, "player:b", Map.of("COBBLESTONE", 64L));
      s.execute("CREATE TABLE auctions(material VARCHAR(64), amount INT, status VARCHAR(16))");
      s.execute("INSERT INTO auctions VALUES('COBBLESTONE', 32, 'OPEN'),('COBBLESTONE', 8, 'SOLD')");
      MarketSupply.replaceHolder(c, "market", MarketSupply.marketStock(c));
      Map<String, Double> first = MarketSupply.recompute(c, 0.25, 1_000);
      assertEquals(160d, first.get("COBBLESTONE"));
      assertEquals(1d, first.get("DRAGON_EGG"));
      MarketSupply.replaceHolder(c, "player:b", Map.of());
      MarketSupply.retainOnline(c, java.util.Set.of("player:a"));
      Map<String, Double> second = MarketSupply.recompute(c, 0.25, 2_000);
      assertEquals(0.25 * 96 + 0.75 * 160, second.get("COBBLESTONE"));
      try (ResultSet r = s.executeQuery("SELECT raw_qty FROM market_supply WHERE item='COBBLESTONE'")) {
        assertTrue(r.next());
        assertEquals(96L, r.getLong(1));
      }
    }
  }

  @Test void shiftClickCraftsUntilTheGridOrTheInventoryRunsOut() {
    assertEquals(64, CraftDemand.crafts(true, new int[] {64, 0, 64}, 1, 64, 36, 0));
    assertEquals(10, CraftDemand.crafts(true, new int[] {64}, 1, 64, 0, 10));
    assertEquals(1, CraftDemand.crafts(false, new int[] {64, 32}, 1, 64, 0, 0));
    assertEquals(0, CraftDemand.crafts(true, new int[] {0, 0}, 1, 64, 36, 0));
    assertEquals(16, CraftDemand.crafts(true, new int[] {64}, 4, 64, 1, 0));
    assertEquals(Map.of("COBBLESTONE", 20L), CraftDemand.consumed(new String[] {"COBBLESTONE", null, "COBBLESTONE"}, 10));
    assertTrue(CraftDemand.consumed(new String[] {"COBBLESTONE"}, 0).isEmpty());
  }

  @Test void craftDemandDecaysWithTheSameHalfLife() {
    long half = 12L * 3_600_000L;
    assertEquals(500d, Scarcity.decay(1000, half, 12), 1e-6);
    assertEquals(1000d, Scarcity.decay(1000, 0, 12));
    assertTrue(Scarcity.decay(1000, half, 72) > 800);
  }

  @Test void massCraftRaisesPriceInsideTheClampAndCannotProfit() {
    var cfg = RecipeArbitrageTest.liveCfg();
    var prices = RecipeArbitrageTest.prices();
    Scarcity.Settings settings = Scarcity.settings(cfg);
    assertEquals(1.0, settings.demandWeight());
    double cobble = Market.quote(cfg, Material.COBBLESTONE, prices.getDouble("base.COBBLESTONE")).base();
    double stone = Market.quote(cfg, Material.STONE, prices.getDouble("base.STONE")).base();
    double idle = Scarcity.factor(cobble, 20_000, settings, false);
    double crafted = Scarcity.factor(cobble, 20_000, 1_000_000_000d, settings, false);
    assertTrue(crafted > idle, crafted + " vs " + idle);
    assertTrue(crafted <= settings.smax() && crafted >= settings.smin());
    assertEquals(settings.smax(), Scarcity.factor(cobble, -1, 1_000_000_000d, settings, false));
    assertEquals(1d, Scarcity.factor(cobble, -1, 0, settings, false));
    double egg = Scarcity.factor(8000, 1, 1_000_000_000d, settings, true);
    assertTrue(egg <= settings.uniqueSmax() && egg > 1);

    long cobbleBuy = CurvePrice.buyCents(CurvePrice.lo(cobble) * settings.smin(), F);
    double stoneCrafted = Scarcity.factor(stone, -1, 1_000_000_000d, settings, false);
    long stoneSell = CurvePrice.sellCents(CurvePrice.hi(stone) * stoneCrafted, cobbleBuy, F);
    assertTrue(stoneSell <= cobbleBuy, stoneSell + " > " + cobbleBuy);
    long roundTripSell = CurvePrice.rawSell(cobble * crafted, F);
    long roundTripBuy = CurvePrice.buy0(cobble * crafted, F);
    assertTrue(roundTripSell * 2L <= roundTripBuy, "sell " + roundTripSell + " buy " + roundTripBuy);
    assertEquals(150000, Market.revenueLimit(cfg));

    Scarcity.Settings mute = new Scarcity.Settings(settings.enabled(), settings.k(), settings.smin(), settings.smax(),
      settings.uniqueSmax(), settings.emaAlpha(), settings.refCheap(), settings.refMid(), settings.refRare(),
      settings.refUnique(), 0, settings.unique());
    assertEquals(Scarcity.factor(cobble, 20_000, mute, false), Scarcity.factor(cobble, 20_000, 1_000_000d, mute, false));
  }

  @Test void demandDecaysInTheRowAndSupplyRefreshDoesNotEraseIt() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:demand_" + UUID.randomUUID() + ";MODE=MySQL")) {
      MarketSupply.migrate(c);
      c.setAutoCommit(false);
      long start = 5_000L;
      MarketSupply.addDemand(c, Map.of("COBBLESTONE", 8000L), start, Map.of("COBBLESTONE", 12d));
      MarketSupply.Sample created = MarketSupply.loadSamples(c).get("COBBLESTONE");
      assertEquals(-1d, created.smoothed());
      assertEquals(8000d, created.demand());
      assertNull(MarketSupply.recompute(c, 0.25, start + 1).get("COBBLESTONE"));
      MarketSupply.Sample kept = MarketSupply.loadSamples(c).get("COBBLESTONE");
      assertEquals(-1d, kept.smoothed());
      assertEquals(8000d, kept.demand());
      long later = start + 12L * 3_600_000L;
      MarketSupply.addDemand(c, Map.of("COBBLESTONE", 8000L), later, Map.of("COBBLESTONE", 12d));
      assertEquals(12_000d, MarketSupply.loadSamples(c).get("COBBLESTONE").demand(), 1e-6);
      MarketSupply.replaceHolder(c, "player:a", Map.of("COBBLESTONE", 64L));
      assertEquals(64d, MarketSupply.recompute(c, 0.25, later + 1).get("COBBLESTONE"));
      MarketSupply.Sample both = MarketSupply.loadSamples(c).get("COBBLESTONE");
      assertEquals(64d, both.smoothed());
      assertEquals(12_000d, both.demand(), 1e-6);
    }
  }
}
