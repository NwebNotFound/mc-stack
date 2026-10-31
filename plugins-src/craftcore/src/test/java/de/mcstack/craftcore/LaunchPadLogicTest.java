package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LaunchPadLogicTest {
  @Test void matchesFeetAndBlockBelowIncludingNegativeCoordinates() {
    var p=new LaunchPadLogic.Pad("id","world",112,-33,90,2,-60,1,5,1.6);
    assertTrue(p.matches("world",112.5,-32,90.5));
    assertTrue(p.matches("world",112.5,-32.9,90.5));
    assertFalse(p.matches("other",112.5,-32,90.5));
    assertFalse(p.matches("world",111.9,-32,90.5));
    assertFalse(p.matches("world",112.5,-31.9,90.5));
  }
  @Test void pressurePlateAtFeetTriggersAndAirUsesBlockBelow() {
    var plate=new LaunchPadLogic.Pad("plate","world",112,-31,89,0,-59,-6,5,1.6);
    assertTrue(plate.matches("world",112.5,-30.9375,89.5));
    assertFalse(LaunchPadLogic.useBlockBelow(false));
    assertTrue(LaunchPadLogic.useBlockBelow(true));
  }
  @Test void entriesCanBeCreatedFoundAndRetargeted() {
    var first=LaunchPadLogic.entry("Spawn", "world", 1, -31, 2, new double[]{0,-59,-6});
    var second=LaunchPadLogic.entry("other", "world", 3, 4, 5, new double[]{1,2,3});
    var entries=new java.util.ArrayList<>(java.util.List.of(first,second));
    assertEquals(0,LaunchPadLogic.indexOf(entries,"spawn"));
    assertEquals(-1,LaunchPadLogic.indexOf(entries,"missing"));
    assertEquals(LaunchPadLogic.target(0,-59,-6),first.get("target"));
    first.put("target",LaunchPadLogic.target(1.25,2.5,-3.75));
    entries.remove(LaunchPadLogic.indexOf(entries,"other"));
    assertEquals(LaunchPadLogic.target(1.25,2.5,-3.75),entries.get(0).get("target"));
    assertEquals(1,entries.size());
  }
  @Test void cooldownBoundary() { assertFalse(LaunchPadLogic.ready(3999,1000,3000)); assertTrue(LaunchPadLogic.ready(4000,1000,3000)); }
  @Test void velocitiesHaveExpectedMagnitudeAndDirection() {
    assertEquals(Math.sqrt(0.8),LaunchPadLogic.upwardVelocity(5),1e-9);
    double[] v=LaunchPadLogic.boost(3,-4,0,1.6);
    assertEquals(.96,v[0],1e-9); assertEquals(-1.28,v[1],1e-9); assertEquals(0,v[2],1e-9);
    assertArrayEquals(new double[]{0,0,0},LaunchPadLogic.boost(0,0,0,1.6));
  }
}
