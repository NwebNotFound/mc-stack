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
  @Test void cooldownBoundary() { assertFalse(LaunchPadLogic.ready(3999,1000,3000)); assertTrue(LaunchPadLogic.ready(4000,1000,3000)); }
  @Test void velocitiesHaveExpectedMagnitudeAndDirection() {
    assertEquals(Math.sqrt(0.8),LaunchPadLogic.upwardVelocity(5),1e-9);
    double[] v=LaunchPadLogic.boost(3,-4,0,1.6);
    assertEquals(.96,v[0],1e-9); assertEquals(-1.28,v[1],1e-9); assertEquals(0,v[2],1e-9);
    assertArrayEquals(new double[]{0,0,0},LaunchPadLogic.boost(0,0,0,1.6));
  }
}
