package de.mcstack.craftcore;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarmupMoveTest {
  @Test void fractionalMovementAndRotationWithinBlockDoNotCancel() {
    Location from=new Location(null,-1.9,64.1,2.2,0,0);
    Location to=new Location(null,-1.1,64.9,2.8,180,45);
    assertFalse(WarmupMove.blockChanged(from.getBlockX(),from.getBlockY(),from.getBlockZ(),to.getBlockX(),to.getBlockY(),to.getBlockZ()));
  }

  @Test void changingAnyBlockCoordinateCancels() {
    assertTrue(WarmupMove.blockChanged(1,2,3,2,2,3));
    assertTrue(WarmupMove.blockChanged(1,2,3,1,3,3));
    assertTrue(WarmupMove.blockChanged(1,2,3,1,2,4));
  }
}
