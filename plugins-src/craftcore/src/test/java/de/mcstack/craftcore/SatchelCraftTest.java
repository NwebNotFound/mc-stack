package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SatchelCraftTest {
  @Test void cappedInputBlocksCraftSmithAndSmelt() {
    assertFalse(SatchelCraft.usesCapped(false, false));
    assertTrue(SatchelCraft.usesCapped(false, true));
    assertTrue(SatchelCraft.usesCapped(true));
  }
}
