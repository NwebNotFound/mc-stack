package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ScarcityTest {
  static Scarcity.Settings on() {
    YamlConfiguration cfg = new YamlConfiguration();
    cfg.set("market.scarcity.enabled", true);
    return Scarcity.settings(cfg);
  }

  @Test void offByDefault() {
    Scarcity.Settings shipped = Scarcity.settings(ArbitrageTest.load("config.yml"));
    assertFalse(shipped.enabled(), "config.yml must ship the flag off");
    assertFalse(Scarcity.settings(new YamlConfiguration()).enabled());
    assertFalse(Scarcity.settings(null).enabled());
    assertEquals(1.0, Scarcity.factor(0.1, 0, 1e9, shipped, false));
  }

  @Test void boundsHoldForAnyInput() {
    Scarcity.Settings s = on();
    Random random = new Random(7);
    double[] odd = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, -1e12, 0, 1e18};
    for (int i = 0; i < 20000; i++) {
      double base = i % 3 == 0 ? 0.1 : i % 3 == 1 ? 10 : 500;
      double supply = i < odd.length * odd.length ? odd[i % odd.length] : random.nextDouble() * Math.pow(10, random.nextInt(9)) - 1;
      double demand = i < odd.length * odd.length ? odd[(i / odd.length) % odd.length] : (random.nextDouble() - 0.3) * Math.pow(10, random.nextInt(9));
      boolean unique = i % 7 == 0;
      double f = Scarcity.factor(base, supply, demand, s, unique);
      assertTrue(Double.isFinite(f), "finite " + supply + "/" + demand);
      assertTrue(f >= s.smin() - 1e-12 && f <= (unique ? s.uniqueSmax() : s.smax()) + 1e-12, f + " out of bounds for " + supply + "/" + demand);
    }
  }

  @Test void cornersAndNeutralPoints() {
    Scarcity.Settings s = on();
    assertEquals(1.0, Scarcity.factor(0.1, -1, 0, s, false), "no sample, no demand = 1");
    assertEquals(s.smax(), Scarcity.factor(500, 0, 0, s, false), 1e-12, "zero stock of a rare item hits smax");
    assertEquals(s.smin(), Scarcity.factor(0.1, 1e12, 0, s, false), 1e-12, "a sea of cobblestone hits smin");
    assertEquals(s.uniqueSmax(), Scarcity.factor(5000, 0, 100, s, true), 1e-12, "the dragon egg may go to 8");
    assertEquals(s.smax(), Scarcity.factor(5000, 0, 100, s, false), 1e-12, "everything else stops at 3");
    assertEquals(1.0, Scarcity.factor(10, 255, 0, s, false), 1e-12, "stock at the reference = 1");
    assertEquals(Scarcity.factor(10, 255, 0, s, false), Scarcity.factor(10, 255, -1e6, s, false), "negative net demand never lowers the price");
    assertTrue(Scarcity.factor(10, 255, 256, s, false) > 1.99, "demand of one reference doubles");
  }

  @Test void settingsRejectNonsense() {
    YamlConfiguration cfg = new YamlConfiguration();
    cfg.set("market.scarcity.enabled", true);
    cfg.set("market.scarcity.smin", -3);
    cfg.set("market.scarcity.smax", 0.2);
    cfg.set("market.scarcity.unique-smax", 1.5);
    cfg.set("market.scarcity.k", Double.NaN);
    cfg.set("market.scarcity.ema-alpha", 7);
    cfg.set("market.scarcity.demand-weight", -1);
    cfg.set("market.scarcity.ref-rare", 0);
    Scarcity.Settings s = Scarcity.settings(cfg);
    assertEquals(0.5, s.smin());
    assertEquals(3.0, s.smax());
    assertTrue(s.uniqueSmax() >= s.smax());
    assertEquals(0.5, s.k());
    assertEquals(0.25, s.emaAlpha());
    assertEquals(1.0, s.demandWeight());
    assertEquals(16, s.refRare());
    assertTrue(s.unique().contains("DRAGON_EGG"));
  }

  @Test void emaAndDecay() {
    assertEquals(40, Scarcity.ema(-1, 40, 0.25), "first sample is adopted");
    assertEquals(32.5, Scarcity.ema(30, 40, 0.25), 1e-12);
    assertEquals(50, Scarcity.decay(100, 72 * 3_600_000L, 72), 1e-9);
    assertEquals(-50, Scarcity.decay(-100, 12 * 3_600_000L, 12), 1e-9, "signed net demand decays symmetrically");
    assertEquals(100, Scarcity.decay(100, -5, 72), "clock going backwards does not grow demand");
  }
}
