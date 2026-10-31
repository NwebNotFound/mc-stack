package de.mcstack.parkour;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParkourRulesTest {
 @Test void climbingClearsPeakBeforeRelease() {
  Double peak=ParkourRules.nextPeak(null,100,99,false,false);
  peak=ParkourRules.nextPeak(peak,99,90,false,true); assertNull(peak);
  peak=ParkourRules.nextPeak(peak,90,89,false,false);
  assertFalse(ParkourRules.fallResets(peak-88,"stone",false));
  assertTrue(ParkourRules.fallResets(peak-84,"stone",false));
 }
 @Test void courseCommandsInvalidateCache() {
  for(String cmd:new String[]{"/parkour create x", "/lpk edit x", "/lobby-parkour delete x", "lobby-parkour:lpk reload", "/reload"}) assertTrue(ParkourRules.isCourseCommand(cmd),cmd);
  assertFalse(ParkourRules.isCourseCommand("/spawn"));
 }
 @Test void slimeNeverResets() { assertFalse(ParkourRules.fallResets(4, "slime_block", false)); assertFalse(ParkourRules.fallResets(40, "slime_block", false)); }
 @Test void softBlocksNeverReset() { for (String b : new String[]{"hay_block", "honey_block", "powder_snow", "water"}) assertFalse(ParkourRules.fallResets(20, b, false), b); assertFalse(ParkourRules.fallResets(20, "red_bed", true)); }
 @Test void shortDropsAreFine() { assertFalse(ParkourRules.fallResets(3.0, "stone", false)); assertFalse(ParkourRules.fallResets(1.25, "stone", false)); }
 @Test void realFallDamageResets() { assertTrue(ParkourRules.fallResets(3.5, "stone", false)); assertTrue(ParkourRules.fallResets(4, "oak_planks", false)); assertTrue(ParkourRules.fallResets(4, null, false)); }
}