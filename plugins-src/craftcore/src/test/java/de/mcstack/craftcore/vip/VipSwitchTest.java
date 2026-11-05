package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VipSwitchTest {
  @Test void leavingTheQueueIsAnnounced() {
    assertEquals("VIP-Warteschlange verlassen: du wechselst zu survival.", VipSwitch.leftQueueText("survival"));
    assertTrue(VipSwitch.leftQueueText(null).contains("anderen Server"));
  }

  @Test void aVipConnectInTheLastTenSecondsCountsAsInFlight() {
    assertTrue(VipSwitch.recent(1_000L, 5_000L));
    assertFalse(VipSwitch.recent(1_000L, 12_000L));
    assertFalse(VipSwitch.recent(null, 5_000L));
    assertFalse(VipSwitch.recent(9_000L, 5_000L), "clock skew never counts");
  }
}
