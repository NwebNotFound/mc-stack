package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortalsTest {
  @Test void regionNormalizesAndContains() {
    Portals.Region r = Portals.parse("survival", "world", List.of(1, -55, -6), List.of(-1, -59, -6), "survival", null, 5);
    assertNotNull(r);
    assertTrue(r.contains("world", 0, -59, -6));
    assertTrue(r.contains("world", -1, -55, -6));
    assertFalse(r.contains("world", 0, -59, -5));
    assertFalse(r.contains("world", 2, -58, -6));
    assertFalse(r.contains("other", 0, -58, -6));
  }
  @Test void invalidConfigRejected() {
    assertNull(Portals.parse("x", "world", List.of(0, 0), List.of(1, 1, 1), "survival", null, 5));
    assertNull(Portals.parse("x", "world", List.of(0, 0, 0), List.of(1, 1, 1), "bad target!", null, 5));
    assertNull(Portals.parse("x", "", List.of(0, 0, 0), List.of(1, 1, 1), "survival", null, 5));
  }
  @Test void cooldown() {
    Map<UUID, Long> last = new HashMap<>(); UUID id = UUID.randomUUID();
    assertTrue(Portals.admit(last, id, 1000, 5000));
    assertFalse(Portals.admit(last, id, 5999, 5000));
    assertTrue(Portals.admit(last, id, 6000, 5000));
  }
  @Test void connectMessage() throws Exception {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(Portals.connectMessage("survival")));
    assertEquals("Connect", in.readUTF());
    assertEquals("survival", in.readUTF());
  }
}
