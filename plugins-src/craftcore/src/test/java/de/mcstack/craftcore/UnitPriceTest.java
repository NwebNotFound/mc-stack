package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnitPriceTest {
  @Test void migrationRoundsUpAndHasMinimum() {
    assertEquals(34, UnitPrice.fromTotal(100, 3));
    assertEquals(25, UnitPrice.fromTotal(100, 4));
    assertEquals(1, UnitPrice.fromTotal(0, 64));
    assertEquals(1, UnitPrice.fromTotal(1, 64));
    assertEquals(Long.MAX_VALUE, UnitPrice.fromTotal(Long.MAX_VALUE, 1));
  }
  @Test void rejectsInvalidAndOverflowingValues() {
    assertThrows(IllegalArgumentException.class, () -> UnitPrice.fromTotal(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> UnitPrice.fromTotal(1, 0));
    assertThrows(IllegalArgumentException.class, () -> UnitPrice.total(0, 1));
    assertThrows(ArithmeticException.class, () -> UnitPrice.total(Long.MAX_VALUE, 2));
    assertEquals(272, UnitPrice.total(34, 8));
  }
}
