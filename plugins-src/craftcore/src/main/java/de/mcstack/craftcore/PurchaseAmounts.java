package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;

/** Mengen, die fuer ein konkretes Angebot gewaehlt werden koennen. */
final class PurchaseAmounts {
  private PurchaseAmounts() {}

  static List<Integer> choices(int available) {
    if (available < 1) throw new IllegalArgumentException("Menge muss positiv sein");
    List<Integer> result = new ArrayList<>();
    for (int amount : new int[]{1, 8, 16, 32, 64}) {
      if (amount <= available) result.add(amount);
    }
    if (!result.contains(available)) result.add(available);
    return List.copyOf(result);
  }
}
