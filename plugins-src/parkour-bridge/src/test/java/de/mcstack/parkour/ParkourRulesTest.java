package de.mcstack.parkour;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ParkourRulesTest {
 @Test void footprintIncludesCenterAndCorners() {
  double[][] points=ParkourRules.footprint(10,20); assertEquals(5,points.length);
  assertArrayEquals(new double[]{10,20},points[0],1e-9);
  assertArrayEquals(new double[]{9.701,19.701},points[1],1e-9);
  assertArrayEquals(new double[]{9.701,20.299},points[2],1e-9);
  assertArrayEquals(new double[]{10.299,19.701},points[3],1e-9);
  assertArrayEquals(new double[]{10.299,20.299},points[4],1e-9);
 }
 @Test void anySoftFootprintBlockPreventsReset() {
  assertTrue(ParkourRules.softLandingAny(List.of("air","slime_block"),false));
  assertFalse(ParkourRules.softLandingAny(List.of("air","stone"),false));
  assertTrue(ParkourRules.softLandingAny(List.of("air","stone"),true));
 }
 @Test void caveRushDropAndFloorOverride() {
  double drop=-32 - (-54);
  assertFalse(ParkourRules.fallResets(drop,"slime_block",false));
  assertFalse(ParkourRules.fallResets(drop,"honey_block",false));
  assertTrue(drop>ParkourRules.SAFE_FALL && ParkourRules.softLandingAny(List.of("air","slime_block"),false));
  Double override=ParkourSql.courseFloor(Map.of("CaVe_RuSh",-60),"cave_rush");
  assertEquals(-60.0,ParkourSql.effectiveFloor(-52.0,override));
  assertTrue(-54>=ParkourSql.effectiveFloor(-52.0,override));
  assertTrue(-54<ParkourSql.effectiveFloor(-52.0,null));
  assertNull(ParkourSql.courseFloor(Map.of("other",-60),"cave_rush"));
  assertNull(ParkourSql.effectiveFloor(null,null));
 }
 @Test void graceExpiresAtDeadline() {
  assertFalse(ParkourRules.inGrace(null,100)); assertTrue(ParkourRules.inGrace(100L,99));
  assertFalse(ParkourRules.inGrace(100L,100)); assertFalse(ParkourRules.inGrace(100L,101));
 }
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
