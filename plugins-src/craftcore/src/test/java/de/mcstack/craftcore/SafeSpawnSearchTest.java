package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SafeSpawnSearchTest {
  @Test void acceptsOnlySafeGroundAndTwoAirBlocks() {
    assertTrue(SafeSpawnSearch.safeBlocks(true, false, false, false, true, true));
    assertFalse(SafeSpawnSearch.safeBlocks(false, false, false, false, true, true));
    assertFalse(SafeSpawnSearch.safeBlocks(true, true, false, false, true, true));
    assertFalse(SafeSpawnSearch.safeBlocks(true, false, true, false, true, true));
    assertFalse(SafeSpawnSearch.safeBlocks(true, false, false, true, true, true));
    assertFalse(SafeSpawnSearch.safeBlocks(true, false, false, false, false, true));
    assertFalse(SafeSpawnSearch.safeBlocks(true, false, false, false, true, false));
  }
  @Test void stopsAfterThirtyFailedAttempts() {
    AtomicInteger calls = new AtomicInteger();
    assertTrue(SafeSpawnSearch.find(() -> 0, (x, z) -> { calls.incrementAndGet(); return false; }).isEmpty());
    assertEquals(30, calls.get());
  }
  @Test void rejectsOutsideCircleAndAcceptsBoundary() {
    int[] coords = {100, 100, 60, 80};
    AtomicInteger index = new AtomicInteger(), checked = new AtomicInteger();
    var result = SafeSpawnSearch.find(() -> coords[index.getAndIncrement()], (x, z) -> { checked.incrementAndGet(); return true; });
    assertEquals(new SafeSpawnSearch.Offset(60, 80), result.orElseThrow());
    assertEquals(1, checked.get());
  }
  @Test void retriesUntilSafe() {
    AtomicInteger checked = new AtomicInteger();
    assertTrue(SafeSpawnSearch.find(() -> 0, (x, z) -> checked.incrementAndGet() == 3).isPresent());
    assertEquals(3, checked.get());
  }
}
