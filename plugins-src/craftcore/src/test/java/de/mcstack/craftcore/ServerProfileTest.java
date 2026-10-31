package de.mcstack.craftcore;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerProfileTest {
  @Test void economyIsDisabledForPvp() {
    assertFalse(ServerProfile.economyEnabled("pvp"));
    assertFalse(ServerProfile.economyEnabled("PvP"));
    assertTrue(ServerProfile.economyEnabled("Lobby"));
    assertTrue(ServerProfile.economyEnabled("Survival"));
    assertTrue(ServerProfile.economyEnabled(null));
  }

  @Test void dbUserDefaultsOrUsesTrimmedValue() {
    assertEquals("craftcore", ServerProfile.dbUser(Map.of()));
    assertEquals("craftcore", ServerProfile.dbUser(Map.of("CRAFTCORE_DB_USER", "  ")));
    assertEquals("craft_pvp", ServerProfile.dbUser(Map.of("CRAFTCORE_DB_USER", " craft_pvp ")));
  }
}
