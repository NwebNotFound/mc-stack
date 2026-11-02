package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SatchelSellTest {
  @Test void payoutNeverExceedsThePullPrice() {
    assertEquals(40, SatchelSell.payout(100, 40L));
    assertEquals(10, SatchelSell.payout(10, 40L));
    assertEquals(0, SatchelSell.payout(0, 40L));
    assertEquals(80, SatchelSell.payout(80, null));
  }

  @Test void stackCapsEachPiece() {
    assertEquals(60, SatchelSell.stackPayout(new long[]{100, 10}, 50L));
    assertEquals(110, SatchelSell.stackPayout(new long[]{100, 10}, null));
  }

  @Test void taggedRewardsStaySellableWhenTheMaterialHasAPrice() {
    assertTrue(SatchelSell.tradable(true, true, false, true));
    assertFalse(SatchelSell.tradable(true, false, true, false));
    assertFalse(SatchelSell.tradable(false, true, false, false));
    assertFalse(SatchelSell.tradable(false, true, true, true));
    assertTrue(SatchelSell.tradable(false, true, true, false));
  }
}
