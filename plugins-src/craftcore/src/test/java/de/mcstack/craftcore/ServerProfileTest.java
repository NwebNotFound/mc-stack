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

  @Test void commandsAreRestrictedWhenEconomyIsDisabled() {
    assertTrue(ServerProfile.commandAllowed(false, "pvp"));
    assertTrue(ServerProfile.commandAllowed(false, "PvPDEBUG"));
    for (String command : new String[]{"balance", "geld", "pay", "bank", "verkaufen", "market", "shop", "ah", "trade", "abholen", "claim", "home", "sethome", "team", "unknown"}) {
      assertFalse(ServerProfile.commandAllowed(false, command), command);
      assertTrue(ServerProfile.commandAllowed(true, command), command);
    }
    assertFalse(ServerProfile.commandAllowed(false, null));
    assertTrue(ServerProfile.commandAllowed(true, "pvp"));
    assertTrue(ServerProfile.commandAllowed(true, "pvpdebug"));
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
