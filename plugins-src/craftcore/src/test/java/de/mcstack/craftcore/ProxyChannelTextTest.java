package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProxyChannelTextTest {
  @Test void aSwitchThatNeverHappensGetsAChatLineWithTheFallback() {
    assertEquals("Der Wechsel nach survival kam beim Proxy nicht an. Versuch es nochmal oder tippe /server survival.", ProxyChannel.notMovedText("survival"));
    assertTrue(ProxyChannel.failureText("survival").contains("fehlgeschlagen"));
  }
}
