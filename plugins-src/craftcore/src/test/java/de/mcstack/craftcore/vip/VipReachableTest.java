package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VipReachableTest {
  @Test void connectWaitsUntilTheSlotAnswersAPing() {
    AtomicInteger pings = new AtomicInteger();
    List<Long> slept = new ArrayList<>();
    assertTrue(VipReachable.waitFor(() -> pings.incrementAndGet() >= 4, 30, 2000, slept::add), "host said online, Paper answers on the 4th ping");
    assertEquals(4, pings.get());
    assertEquals(List.of(2000L, 2000L, 2000L), slept, "no connect before the ping answered");
  }

  @Test void aSlotThatNeverAnswersGivesAClearMessageNotAConnect() {
    AtomicInteger pings = new AtomicInteger();
    assertFalse(VipReachable.waitFor(() -> { pings.incrementAndGet(); return false; }, 5, 1, ms -> {}));
    assertEquals(5, pings.get(), "bounded");
    assertTrue(VipReachable.NOT_REACHABLE.contains("Beitreten"));
  }

  @Test void oneRetryThenAClearMessage() {
    assertEquals(VipReachable.RETRYING, VipReachable.afterStillHere(1));
    assertEquals(VipReachable.NOT_REACHABLE, VipReachable.afterStillHere(2));
    assertEquals(2, VipReachable.CONNECT_ATTEMPTS);
  }
}
