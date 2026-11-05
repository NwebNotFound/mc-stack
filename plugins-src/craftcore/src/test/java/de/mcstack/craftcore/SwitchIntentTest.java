package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SwitchIntentTest {
  @Test void aDelayedSurvivalRetryNeverOverridesANewerChoice() {
    SwitchIntent intent = new SwitchIntent();
    UUID nils = UUID.randomUUID();
    long survival = intent.begin(nils);      // portal to survival, retry scheduled with this token
    long vip = intent.begin(nils);           // then he picks his VIP world in the menu
    assertFalse(intent.current(nils, survival), "the 3 s / 5 s survival retry must not fire any more");
    assertTrue(intent.current(nils, vip));
    assertEquals(vip, intent.latestFor(nils));
  }

  @Test void playersAreIndependent() {
    SwitchIntent intent = new SwitchIntent();
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    long ta = intent.begin(a);
    intent.begin(b);
    assertTrue(intent.current(a, ta));
    intent.forget(a);
    assertFalse(intent.current(a, ta));
  }
}
