package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandModuleGuardTest {
  @Test void missingModuleReportsUnavailableAndStopsDispatch() {
    List<String> replies = new ArrayList<>();
    assertFalse(CraftCore.moduleAvailable(null, replies::add));
    assertEquals(List.of("Dieser Befehl ist auf diesem Server nicht verfügbar."), replies);
  }

  @Test void availableModuleAllowsDispatchWithoutReply() {
    List<String> replies = new ArrayList<>();
    assertTrue(CraftCore.moduleAvailable(new Object(), replies::add));
    assertTrue(replies.isEmpty());
  }
}
