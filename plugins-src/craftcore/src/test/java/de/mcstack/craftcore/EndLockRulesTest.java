package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EndLockRulesTest {
  @Test void enabledAndBypassRequireBothOpAndPermission() {
    assertFalse(EndLockRules.blocked(false, false, false));
    assertFalse(EndLockRules.blocked(false, true, true));
    assertTrue(EndLockRules.blocked(true, false, false));
    assertTrue(EndLockRules.blocked(true, true, false));
    assertTrue(EndLockRules.blocked(true, false, true));
    assertFalse(EndLockRules.blocked(true, true, true));
  }
  @Test void cooldownIsPerPlayerAndStartsAtLastMessage() {
    EndLockRules rules = new EndLockRules();
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    assertTrue(rules.shouldNotify(a, 1000));
    assertFalse(rules.shouldNotify(a, 3999));
    assertTrue(rules.shouldNotify(b, 3999));
    assertTrue(rules.shouldNotify(a, 4000));
    assertFalse(rules.shouldNotify(a, 4001));
  }
}
