package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StartupGateTest {
  @Test void databaseDownAtBootStartsModulesOnceItAnswers() {
    AtomicInteger calls = new AtomicInteger(), starts = new AtomicInteger();
    StartupGate gate = new StartupGate(() -> calls.incrementAndGet() > 3, starts::incrementAndGet);
    assertFalse(gate.attempt(), "boot: db down, plugin stays enabled and waits");
    assertFalse(gate.attempt());
    assertFalse(gate.attempt());
    assertTrue(gate.attempt(), "db answers on the 4th probe");
    assertTrue(gate.attempt());
    assertEquals(1, starts.get(), "modules start exactly once");
    assertEquals(4, calls.get(), "no probing after the start");
  }

  @Test void databaseUpAtBootStartsImmediately() {
    AtomicInteger starts = new AtomicInteger();
    StartupGate gate = new StartupGate(() -> true, starts::incrementAndGet);
    assertTrue(gate.attempt());
    assertEquals(1, starts.get());
  }

  @Test void playersGetAClearMessageInsteadOfSilence() {
    assertTrue(CraftCore.NOT_READY.contains("Datenbank") && CraftCore.NOT_READY.contains("Server-Wechsel"));
  }
}
