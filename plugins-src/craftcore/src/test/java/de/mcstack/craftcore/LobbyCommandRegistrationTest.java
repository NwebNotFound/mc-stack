package de.mcstack.craftcore;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * /lobby, /l, /hub, /leave and /spawn have to exist on every backend. The route and plugin.yml must
 * agree, otherwise one spelling silently falls through to "unknown command" again.
 */
class LobbyCommandRegistrationTest {
  @Test void routeAndPluginYmlRegisterTheSameNames() throws Exception {
    YamlConfiguration config = YamlConfiguration.loadConfiguration(
      new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
    List<String> registered = new ArrayList<>();
    if (config.contains("commands.lobby")) {
      registered.add("lobby");
      registered.addAll(config.getStringList("commands.lobby.aliases"));
    }
    if (config.contains("commands.spawn")) registered.add("spawn");
    assertEquals(new TreeSet<>(SpawnRoute.LOBBY_COMMANDS), new TreeSet<>(registered));
  }

  @Test void lobbyIsNotGatedByAPermission() throws Exception {
    YamlConfiguration config = YamlConfiguration.loadConfiguration(
      new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
    assertFalse(config.contains("commands.lobby.permission"), "jeder Spieler muss die Lobby erreichen");
    assertFalse(config.contains("commands.spawn.permission"), "jeder Spieler muss zum Spawn zurueck");
  }
}
