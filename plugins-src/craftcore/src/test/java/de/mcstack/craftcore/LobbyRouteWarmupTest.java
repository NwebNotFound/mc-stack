package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Warmup and removal fallbacks that used to be untested inline logic. */
class LobbyRouteWarmupTest {
  @Test void lookingAroundDoesNotBreakTheWarmup() {
    assertFalse(LobbyRoute.breaksWarmup(true, false));
  }

  @Test void walkingToAnotherBlockBreaksTheWarmup() {
    assertTrue(LobbyRoute.breaksWarmup(true, true));
  }

  @Test void worldChangeBreaksTheWarmup() {
    assertTrue(LobbyRoute.breaksWarmup(false, false));
  }

  @Test void aMemberWhoCannotBeSentToTheLobbyIsKicked() {
    assertTrue(de.mcstack.craftcore.vip.VipWorldGate.kickAfterRemoval(false));
    assertFalse(de.mcstack.craftcore.vip.VipWorldGate.kickAfterRemoval(true));
  }
}
