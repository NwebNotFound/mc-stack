package de.mcstack.parkour;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParkourRulesTest {
 @Test void slimeNeverResets() { assertFalse(ParkourRules.fallResets(4, "slime_block", false)); assertFalse(ParkourRules.fallResets(40, "slime_block", false)); }
 @Test void softBlocksNeverReset() { for (String b : new String[]{"hay_block", "honey_block", "powder_snow", "water"}) assertFalse(ParkourRules.fallResets(20, b, false), b); assertFalse(ParkourRules.fallResets(20, "red_bed", true)); }
 @Test void shortDropsAreFine() { assertFalse(ParkourRules.fallResets(3.0, "stone", false)); assertFalse(ParkourRules.fallResets(1.25, "stone", false)); }
 @Test void realFallDamageResets() { assertTrue(ParkourRules.fallResets(3.5, "stone", false)); assertTrue(ParkourRules.fallResets(4, "oak_planks", false)); assertTrue(ParkourRules.fallResets(4, null, false)); }
}