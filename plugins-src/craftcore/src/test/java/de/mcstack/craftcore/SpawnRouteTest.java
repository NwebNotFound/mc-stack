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
  @Test void connectEncodesAnyTargetServer() throws Exception {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(SpawnRoute.connect("survival")));
    assertEquals("Connect", in.readUTF());
    assertEquals("survival", in.readUTF());
    assertEquals(-1, in.read());
  }
  @Test void sendChannelIsTheVelocityBungeeMainChannel() {
    assertEquals("bungeecord:main", SpawnRoute.CHANNEL);
    assertEquals("BungeeCord", SpawnRoute.LEGACY_CHANNEL);
  }
  @Test void everyLobbySpellingIsRouted() {
    for (String name : java.util.List.of("lobby", "L", "Hub", "leave", "SPAWN", "Spawn")) {
      assertTrue(SpawnRoute.isLobbyCommand(name), name);
    }
    assertFalse(SpawnRoute.isLobbyCommand("home"));
    assertFalse(SpawnRoute.isLobbyCommand("spawnpoint"));
    assertFalse(SpawnRoute.isLobbyCommand(null));
  }
  @Test void serverRestrictionsIgnoreCase() {
    assertTrue(SpawnRoute.isLobby("Lobby"));
    assertTrue(SpawnRoute.isSurvival("Survival"));
    assertFalse(SpawnRoute.isSurvival("Lobby"));
    assertFalse(SpawnRoute.isLobby("Survival"));
  }
}
