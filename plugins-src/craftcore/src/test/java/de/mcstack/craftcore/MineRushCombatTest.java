package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MineRushCombatTest {
  @Test void dodgeCostsStaminaAndHasExclusiveEndBoundaries() {
    var dodge = new MineRushCombat.Dodge(100, 40, 1, 5, 10);
    assertTrue(dodge.roll(100)); assertEquals(60, dodge.stamina());
    assertTrue(dodge.immune(100)); assertTrue(dodge.immune(104)); assertFalse(dodge.immune(105));
    assertFalse(dodge.roll(109)); assertTrue(dodge.roll(110)); assertEquals(30, dodge.stamina());
    assertFalse(dodge.roll(119)); assertTrue(dodge.roll(120)); dodge.advance(130);
    assertThrows(IllegalArgumentException.class, () -> dodge.advance(129));
  }
  @Test void staggerResetsAndRejectsInvalidDamage() {
    var poise = new MineRushCombat.Poise(100);
    assertFalse(poise.hit(60)); assertTrue(poise.hit(40)); assertEquals(100, poise.remaining());
    assertThrows(IllegalArgumentException.class, () -> poise.hit(Double.NaN));
  }
  @Test void phaseThresholdsAndParametersAreValidated() {
    var attack = new MineRushCombat.Attack(MineRushCombat.Ability.SLAM, 20, 30, 100, 8, 5);
    var phases = List.of(new MineRushCombat.Phase(1, List.of(attack)), new MineRushCombat.Phase(.5, List.of(attack)));
    assertEquals(0, MineRushCombat.phase(51,100,phases)); assertEquals(1, MineRushCombat.phase(50,100,phases));
    assertThrows(IllegalArgumentException.class, () -> MineRushCombat.phase(50,100,List.of(phases.get(1),phases.get(0))));
    assertThrows(IllegalArgumentException.class, () -> new MineRushCombat.Attack(MineRushCombat.Ability.LASER, 0, 0, 1, 1, 1));
  }
}
