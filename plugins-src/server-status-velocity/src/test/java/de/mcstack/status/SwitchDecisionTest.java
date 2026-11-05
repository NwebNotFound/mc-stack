package de.mcstack.status;

import static org.junit.jupiter.api.Assertions.*;
import static de.mcstack.status.SwitchDecision.Action.*;

import org.junit.jupiter.api.Test;

class SwitchDecisionTest {
  @Test void oldSessionsAndChangedBackendsAreRejected() {
    assertFalse(SwitchDecision.belongsToSession(1000, 2000L, "lobby", "lobby"));
    assertFalse(SwitchDecision.belongsToSession(3000, 2000L, "lobby", "vipworld-1"));
    assertFalse(SwitchDecision.belongsToSession(3000, null, "lobby", "lobby"));
    assertTrue(SwitchDecision.belongsToSession(3000, 2000L, "lobby", "lobby"));
  }
  @Test void thePluginMessageGetsAHeadStart() {
    assertEquals(WAIT, SwitchDecision.decide(1_000, 2_000, true, "lobby", "survival", true));
  }

  @Test void aSwitchThePluginMessageMissedIsDoneByTheProxy() {
    assertEquals(CONNECT, SwitchDecision.decide(1_000, 6_000, true, "lobby", "survival", true));
  }

  @Test void noDoubleSwitchWhenThePluginMessageWorked() {
    assertEquals(ALREADY_THERE, SwitchDecision.decide(1_000, 6_000, true, "survival", "survival", true));
  }

  @Test void offlineUnknownAndOldRequestsAreDropped() {
    assertEquals(OFFLINE, SwitchDecision.decide(1_000, 6_000, false, null, "survival", true));
    assertEquals(UNKNOWN_SERVER, SwitchDecision.decide(1_000, 6_000, true, "lobby", "nope", false));
    assertEquals(EXPIRED, SwitchDecision.decide(1_000, 20_000, true, "lobby", "survival", true));
  }
}
