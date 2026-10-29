package de.mcstack.craftcore;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnRouteTest {
  @Test void connectUsesVelocityBungeeProtocol() throws Exception {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(SpawnRoute.connectMessage()));
    assertEquals("Connect", in.readUTF());
    assertEquals("lobby", in.readUTF());
    assertEquals(-1, in.read());
  }
  @Test void serverRestrictionsIgnoreCase() {
    assertTrue(SpawnRoute.isLobby("Lobby"));
    assertTrue(SpawnRoute.isSurvival("Survival"));
    assertFalse(SpawnRoute.isSurvival("Lobby"));
    assertFalse(SpawnRoute.isLobby("Survival"));
  }
}
