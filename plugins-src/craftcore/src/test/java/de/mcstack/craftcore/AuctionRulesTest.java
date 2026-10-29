package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuctionRulesTest {
  @Test void durationBoundaries() {
    assertFalse(AuctionRules.validDuration(AuctionRules.HOUR - 1));
    assertTrue(AuctionRules.validDuration(AuctionRules.HOUR));
    assertTrue(AuctionRules.validDuration(72 * AuctionRules.HOUR));
    assertFalse(AuctionRules.validDuration  (72 * AuctionRules.HOUR + 1));
  }
  @Test void minimumIncrementIsAtLeastOneCpAndRoundsUp() {
    assertEquals(101, AuctionRules.minimumNext(1));
    assertEquals(2100, AuctionRules.minimumNext(2000));
    assertEquals(2102, AuctionRules.minimumNext(2001));
    assertThrows(ArithmeticException.class, () -> AuctionRules.minimumNext(Long.MAX_VALUE));
  }
  @Test void lateBidExtendsExistingEnd() {
    assertEquals(500_000, AuctionRules.extendedEnd(379_999, 500_000));
    assertEquals(620_000, AuctionRules.extendedEnd(380_000, 500_000));
    assertThrows(IllegalArgumentException.class, () -> AuctionRules.extendedEnd(500_000, 500_000));
  }
}
