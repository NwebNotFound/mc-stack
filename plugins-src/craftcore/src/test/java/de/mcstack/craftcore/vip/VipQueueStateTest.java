package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VipQueueStateTest {
  final UUID nils = UUID.randomUUID();

  @Test void portalWhileQueuedLeavesTheQueueWithAChatNote() {
    VipQueueState q = new VipQueueState();
    q.waiting.put(nils, "world-1");               // VIP world is starting, player waits
    int before = q.generation(nils);
    VipQueueState.Left left = q.beforeSwitch(nils, "survival", 10_000);
    assertNotNull(left, "the Survival portal is not ignored while queued");
    assertEquals("world-1", left.world(), "the coordinator is told to drop this waiter");
    assertEquals("VIP-Warteschlange verlassen: du wechselst zu survival.", left.message());
    assertFalse(left.recentConnect(), "no VIP connect was sent yet, so no resend is needed");
    assertNotEquals(before, q.generation(nils), "the pending wait and its auto-connect are dropped");
    assertFalse(q.waiting.containsKey(nils));
  }

  @Test void portalRightAfterTheVipConnectAsksForAResend() {
    VipQueueState q = new VipQueueState();
    q.sentSlot.put(nils, "vipworld-1");
    q.sentAt.put(nils, 9_000L);
    VipQueueState.Left left = q.beforeSwitch(nils, "survival", 11_000);
    assertTrue(left.recentConnect(), "Velocity may still process our VIP connect: resend the Survival switch");
  }

  @Test void ourOwnVipConnectAndUnqueuedPlayersAreUntouched() {
    VipQueueState q = new VipQueueState();
    q.sentSlot.put(nils, "vipworld-1");
    assertNull(q.beforeSwitch(nils, "vipworld-1", 0), "the VIP auto-connect itself does not cancel the queue");
    assertNull(q.beforeSwitch(UUID.randomUUID(), "survival", 0), "a player without a queue switches normally");
  }

  @Test void quitForgetsThePlayerAndARejoinNeverRevivesAnOldRetry() {
    // Issue #158: the generation map grew by one entry per player and was never cleaned up.
    VipQueueState q = new VipQueueState();
    int old = q.generation(nils);
    assertTrue(q.current(nils, old));
    q.waiting.put(nils, "world-1");
    q.forget(nils);
    assertTrue(q.generation.isEmpty() && q.waiting.isEmpty());
    assertFalse(q.current(nils, old), "a retry still running after the quit is dropped");
    assertTrue(q.generation.isEmpty(), "checking a late retry does not create the entry again");
    int rejoined = q.generation(nils);
    assertNotEquals(old, rejoined, "after a rejoin the old generation never matches again");
    assertFalse(q.current(nils, old));
  }
}
