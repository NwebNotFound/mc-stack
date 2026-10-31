package de.mcstack.craftcore;

import java.util.Map;
import java.util.Locale;
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

  @Test void commandsAreRestrictedWhenEconomyIsDisabled() {
    for (String command : new String[]{"balance", "pay", "bank", "verkaufen", "market", "shop", "ah", "trade", "abholen", "testrun"}) {
      assertFalse(ServerProfile.commandAllowed(false, command), command);
      assertTrue(ServerProfile.commandAllowed(true, command), command);
      String uppercase = command.toUpperCase(Locale.ROOT);
      assertFalse(ServerProfile.commandAllowed(false, uppercase), uppercase);
      assertTrue(ServerProfile.commandAllowed(true, uppercase), uppercase);
    }
    for (String command : new String[]{"pvp", "pvpdebug", "cc", "rank", "sidebar", "spawn", "help", "menu", "stats", "npc", "vw"}) {
      assertTrue(ServerProfile.commandAllowed(false, command), command);
    }
    assertFalse(ServerProfile.commandAllowed(false, null));
    assertTrue(ServerProfile.commandAllowed(true, null));
  }

  @Test void pvpRequiresPasswordAndDriver() {
    assertTrue(ServerProfile.pvpStartupError(null, true).contains("CRAFTCORE_DB_PASSWORD"));
    assertTrue(ServerProfile.pvpStartupError("  ", true).contains("CRAFTCORE_DB_PASSWORD"));
    assertTrue(ServerProfile.pvpStartupError("secret", false).contains("MariaDB"));
    assertNull(ServerProfile.pvpStartupError("secret", true));
  }

  @Test void dbUserDefaultsOrUsesTrimmedValue() {
    assertEquals("craftcore", ServerProfile.dbUser(Map.of()));
    assertEquals("craftcore", ServerProfile.dbUser(Map.of("CRAFTCORE_DB_USER", "  ")));
    assertEquals("craft_pvp", ServerProfile.dbUser(Map.of("CRAFTCORE_DB_USER", " craft_pvp ")));
  }
}
