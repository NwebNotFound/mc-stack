package de.mcstack.craftcore;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseAmountsTest {
  @Test void offersOnlyAvailableAmountsAndAll() {
    assertEquals(List.of(1), PurchaseAmounts.choices(1));
    assertEquals(List.of(1, 8, 13), PurchaseAmounts.choices(13));
    assertEquals(List.of(1, 8, 16, 32, 64), PurchaseAmounts.choices(64));
    assertEquals(List.of(1, 8, 16, 32, 64, 65), PurchaseAmounts.choices(65));
    assertThrows(IllegalArgumentException.class, () -> PurchaseAmounts.choices(0));
  }
}
