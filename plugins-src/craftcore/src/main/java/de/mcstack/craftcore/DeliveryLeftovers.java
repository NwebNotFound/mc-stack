package de.mcstack.craftcore;

import java.util.Collection;

final class DeliveryLeftovers {
  static int sum(Collection<Integer> amounts) {
    return amounts.stream().mapToInt(Integer::intValue).sum();
  }

  static String message(int amount) {
    return amount + " Items bleiben im Abholfach.";
  }
}
