package de.mcstack.craftcore;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;

final class SafeSpawnSearch {
  static final int RADIUS = 100, ATTEMPTS = 30;
  record Offset(int x, int z) {}

  static Optional<Offset> find(IntSupplier coordinate, BiPredicate<Integer, Integer> safe) {
    for (int i = 0; i < ATTEMPTS; i++) {
      int x = coordinate.getAsInt(), z = coordinate.getAsInt();
      if (x * x + z * z <= RADIUS * RADIUS && safe.test(x, z)) return Optional.of(new Offset(x, z));
    }
    return Optional.empty();
  }
  static int randomCoordinate() { return ThreadLocalRandom.current().nextInt(-RADIUS, RADIUS + 1); }
  static boolean safeBlocks(boolean solid, boolean leaves, boolean liquid, boolean waterlogged, boolean air1, boolean air2) {
    return solid && !leaves && !liquid && !waterlogged && air1 && air2;
  }
}
