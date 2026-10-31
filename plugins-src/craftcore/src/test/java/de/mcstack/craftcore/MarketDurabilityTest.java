package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketDurabilityTest {
  @Test void remainingDurabilityReducesThePriceAndRoundsDown() {
    assertEquals(100, Market.durabilityPrice(100, 0, 100));
    assertEquals(50, Market.durabilityPrice(100, 50, 100));
    assertEquals(0, Market.durabilityPrice(1, 1, 100));
    assertEquals(0, Market.durabilityPrice(100, 101, 100));
    assertEquals(100, Market.durabilityPrice(100, -1, 100));
    assertEquals(100, Market.durabilityPrice(100, 0, 0));
  }
}
