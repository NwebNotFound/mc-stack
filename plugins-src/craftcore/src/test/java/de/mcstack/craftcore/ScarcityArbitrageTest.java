package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * With scarcity on, no recipe (and no reversible pair) becomes a profit loop: the recipe cap reads the same scaled
 * ingredient buy the player pays, and crafting back and forth leaves no net demand behind.
 */
class ScarcityArbitrageTest {
  static final YamlConfiguration CFG = RecipeArbitrageTest.liveCfg();
  static final YamlConfiguration PRICES = RecipeArbitrageTest.prices();
  static final CurvePrice.Factors F = CurvePrice.factors(CFG);
  static final Scarcity.Settings S = ScarcityTest.on();

  static double base(Material m) { return Market.quote(CFG, m, PRICES.getDouble("base." + m.name())).base(); }
  static boolean priced(Material m) { return PRICES.isSet("base." + m.name()) && !Market.unbuyable(CFG, m); }

  /** Worst corner: every ingredient as cheap as scarcity allows (abundance never lowers a bank buy), the output as dear as it allows. */
  @Test void everyRecipeStaysCappedAtTheScarcityCorners() throws Exception {
    Set<String> unsellable = new HashSet<>(CFG.getStringList("market.unsellable"));
    int checked = 0;
    for (RecipeArbitrageTest.R recipe : RecipeArbitrageTest.recipes()) {
      if (recipe.count() <= 0 || recipe.selfCopy()) continue;
      if (!PRICES.isSet("base." + recipe.out().name()) || unsellable.contains(recipe.out().name())) continue;
      long cost = 0;
      boolean ok = true;
      for (List<Material> slot : recipe.slots()) {
        long best = Long.MAX_VALUE;
        for (Material option : slot) if (priced(option)) best = Math.min(best, CurvePrice.scaledBuy(base(option) * 0.25, S.smin(), F));
        if (best == Long.MAX_VALUE) { ok = false; break; }
        cost += best;
      }
      if (!ok) continue;
      checked++;
      boolean unique = S.unique().contains(recipe.out().name());
      double high = base(recipe.out()) * 1.2 * (unique ? S.uniqueSmax() : S.smax());
      long capped = recipe.count() * CurvePrice.sellCents(high, cost / recipe.count(), F);
      assertTrue(capped <= cost, recipe.out() + " x" + recipe.count() + " " + capped + " > " + cost);
    }
    assertTrue(checked > 1000, "checked " + checked);
  }

  /** Buy the inputs, craft, sell the output, for both directions of every reversible pair, at any factor mix. */
  @Test void reversiblePairsHaveNoProfitLoop() {
    double[] corners = {S.smin(), 1, S.smax()};
    int pairs = 0;
    for (ArbitrageTest.Recipe forward : ArbitrageTest.RECIPES) {
      ArbitrageTest.Recipe back = ArbitrageTest.RECIPES.stream()
        .filter(r -> r.input() == forward.output() && r.output() == forward.input()).findFirst().orElse(null);
      if (back == null || !priced(forward.input()) || !priced(forward.output())) continue;
      pairs++;
      for (double sIn : corners) for (double sOut : corners) {
        long cost = forward.inputs() * CurvePrice.scaledBuy(base(forward.input()), sIn, F);
        long cap = cost / forward.outputs();
        long revenue = forward.outputs() * CurvePrice.sellCents(base(forward.output()) * sOut, cap, F);
        assertTrue(revenue <= cost, forward + " at " + sIn + "/" + sOut + ": " + revenue + " > " + cost);
      }
    }
    assertTrue(pairs >= 20, "pairs " + pairs);
  }

  /** The demand part of the factor: 10000 round trips through every reversible pair leave the price where it was. */
  @Test void craftingBackAndForthDoesNotPumpThePrice() {
    for (ArbitrageTest.Recipe forward : ArbitrageTest.RECIPES) {
      ArbitrageTest.Recipe back = ArbitrageTest.RECIPES.stream()
        .filter(r -> r.input() == forward.output() && r.output() == forward.input()).findFirst().orElse(null);
      if (back == null) continue;
      DemandLedger ledger = new DemandLedger();
      String[] in = new String[forward.inputs()];
      Arrays.fill(in, forward.input().name());
      String[] out = new String[back.inputs()];
      Arrays.fill(out, back.input().name());
      for (int i = 0; i < 10_000; i++) {
        ledger.add(CraftDemand.craftDelta(in, 1, forward.output().name(), forward.outputs()));
        ledger.add(CraftDemand.craftDelta(out, 1, back.output().name(), back.outputs()));
      }
      Map<String, Long> net = ledger.drain();
      for (Material m : List.of(forward.input(), forward.output())) {
        double demand = net.getOrDefault(m.name(), 0L);
        assertEquals(Scarcity.factor(10, 255, 0, S, false), Scarcity.factor(10, 255, demand, S, false), 1e-12, m + " pumped by " + demand);
      }
    }
  }

  @Test void abundanceNeverMakesTheBankSellCheaper() {
    for (double base : new double[] {0.05, 0.9, 7, 160}) {
      CurvePrice.Cent low = CurvePrice.scaled(base, S.smin(), null, F), plain = CurvePrice.scaled(base, 1, null, F);
      assertEquals(plain.buy(), low.buy(), "buy at " + base);
      assertTrue(low.sell() <= plain.sell() && low.sell() < low.buy(), "sell at " + base);
    }
  }

  /** Sell stays at most half of buy on the same scaled mid, at every factor. */
  @Test void sellStaysBelowHalfTheBuyAtEveryFactor() {
    for (double base : new double[] {0.05, 0.9, 7, 160, 3200}) for (double s = S.smin(); s <= S.uniqueSmax(); s += 0.25) {
      long buy = CurvePrice.buyCents(base * s, F), sell = CurvePrice.sellCents(base * s, null, F);
      assertTrue(sell < buy, base + "*" + s);
      assertTrue(sell <= Math.max(1, buy / 2) + 1, base + "*" + s + ": " + sell + " vs " + buy);
    }
  }
}
