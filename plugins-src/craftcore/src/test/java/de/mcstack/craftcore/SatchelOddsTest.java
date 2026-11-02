package de.mcstack.craftcore;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SatchelOddsTest {
  private static SatchelOdds.Entry entry(int id, SatchelOdds.Rarity rarity, int weight) {
    return new SatchelOdds.Entry(id, rarity, weight);
  }

  @Test void sharesMatchWeights() {
    var shares = SatchelOdds.shares(List.of(entry(1, SatchelOdds.Rarity.C, 50), entry(2, SatchelOdds.Rarity.SSR, 25), entry(3, SatchelOdds.Rarity.SSR_PLUS, 25)));
    assertEquals(5000, shares.get(0).basisPoints());
    assertEquals("50.00%", shares.get(0).percent());
    assertEquals(0, shares.get(1).basisPoints());
    assertEquals(2500, shares.get(SatchelOdds.Rarity.SSR.ordinal()).basisPoints());
    assertEquals(2500, shares.get(SatchelOdds.Rarity.SSR_PLUS.ordinal()).basisPoints());
    int sum = 0;
    for (var share : shares) sum += share.basisPoints();
    assertEquals(10000, sum);
  }

  @Test void oddsStayNearTheWeights() {
    var pool = List.of(entry(1, SatchelOdds.Rarity.C, 50), entry(2, SatchelOdds.Rarity.UC, 30), entry(3, SatchelOdds.Rarity.R, 20));
    int[] counts = new int[4];
    var pity = new SatchelOdds.Pity(0, 0);
    var rng = new Random(42);
    int n = 20_000;
    for (var outcome : SatchelOdds.pull(pool, pity, n, 1_000_000, 1_000_000, rng)) counts[outcome.entry().id()]++;
    assertEquals(n, counts[1] + counts[2] + counts[3]);
    assertTrue(Math.abs(counts[1] - 10_000) < 400, "C " + counts[1]);
    assertTrue(Math.abs(counts[2] - 6_000) < 400, "UC " + counts[2]);
    assertTrue(Math.abs(counts[3] - 4_000) < 400, "R " + counts[3]);
  }

  @Test void pityForcesSrThenSsrAndResets() {
    var pool = List.of(entry(1, SatchelOdds.Rarity.C, 1000), entry(2, SatchelOdds.Rarity.SR, 1), entry(3, SatchelOdds.Rarity.SSR, 1), entry(4, SatchelOdds.Rarity.SSR_PLUS, 1));
    var rng = new Random(1);
    var forcedSr = SatchelOdds.roll(pool, new SatchelOdds.Pity(9, 0), 10, 50, rng);
    assertTrue(forcedSr.entry().rarity().atLeast(SatchelOdds.Rarity.SR));
    assertEquals(0, forcedSr.pity().sinceSr());
    assertTrue(forcedSr.pity().sinceSsr() > 0 || forcedSr.entry().rarity().atLeast(SatchelOdds.Rarity.SSR));

    var forcedSsr = SatchelOdds.roll(pool, new SatchelOdds.Pity(0, 49), 10, 50, rng);
    assertTrue(forcedSsr.entry().rarity().atLeast(SatchelOdds.Rarity.SSR));
    assertEquals(new SatchelOdds.Pity(0, 0), forcedSsr.pity());

    var common = SatchelOdds.roll(List.of(entry(1, SatchelOdds.Rarity.C, 1)), new SatchelOdds.Pity(0, 3), 10, 50, rng);
    assertEquals(new SatchelOdds.Pity(1, 4), common.pity());
    var sr = SatchelOdds.roll(List.of(entry(2, SatchelOdds.Rarity.SR, 1)), new SatchelOdds.Pity(4, 7), 10, 50, rng);
    assertEquals(new SatchelOdds.Pity(0, 8), sr.pity());
  }

  @Test void tenPullCarriesPity() {
    var pool = List.of(entry(1, SatchelOdds.Rarity.C, 1));
    var rolls = SatchelOdds.pull(pool, new SatchelOdds.Pity(0, 0), 10, 100, 100, new Random(1));
    assertEquals(10, rolls.size());
    assertEquals(new SatchelOdds.Pity(10, 10), rolls.get(9).pity());
  }

  @Test void rarityNames() {
    assertEquals(SatchelOdds.Rarity.SSR_PLUS, SatchelOdds.Rarity.parse("ssr+"));
    assertEquals(SatchelOdds.Rarity.SSR_PLUS, SatchelOdds.Rarity.parse("SSR_PLUS"));
    assertNull(SatchelOdds.Rarity.parse("legendary"));
  }
}
