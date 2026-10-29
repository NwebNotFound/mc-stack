package de.mcstack.craftcore;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static de.mcstack.craftcore.MineRushRoom.*;

class MineRushRoomTest {
  private Definition room() {
    return new Definition("test", Type.KAMPF, 5, 4, 3, List.of(
        new Marker(MarkerType.ENTRANCE, new Point(2, 1, 0), Facing.NORTH),
        new Marker(MarkerType.EXIT, new Point(4, 1, 1), Facing.EAST)));
  }
  @Test void validatesConnectionsAndRequiredMarkers() {
    assertTrue(validate(room()).isEmpty());
    assertFalse(validate(new Definition("../unsafe", Type.PUZZLE, 5, 4, 3, room().markers())).isEmpty());
    assertFalse(validate(new Definition("boss", Type.BOSS, 5, 4, 3, room().markers())).isEmpty());
    assertFalse(validate(new Definition("bad", Type.KAMPF, 5, 4, 3,
        List.of(new Marker(MarkerType.ENTRANCE, new Point(2, 1, 1), Facing.NORTH)))).isEmpty());
  }
  @Test void allRotationsConnectFacingDoorsAndStayAdjacent() {
    for (Facing exit : Facing.values()) {
      Point previous = new Point(100, 12, -50);
      Placement placement = connect(previous, exit, room());
      Marker entrance = room().markers().get(0);
      Point actual = placement.origin().plus(rotate(entrance.position(), room().width(), room().depth(), placement.quarterTurns()));
      assertEquals(previous.plus(new Point(exit.dx, 0, exit.dz)), actual);
      assertEquals(exit.opposite(), entrance.facing().rotate(placement.quarterTurns()));
    }
  }
  @Test void rotationOfRectangularRoomUsesCorrectDimensions() {
    assertEquals(new Point(2, 1, 4), rotate(new Point(4, 1, 0), 5, 3, 1));
    assertEquals(new Point(0, 1, 2), rotate(new Point(4, 1, 0), 5, 3, 2));
    assertEquals(new Point(0, 1, 0), rotate(new Point(4, 1, 0), 5, 3, 3));
  }
}
