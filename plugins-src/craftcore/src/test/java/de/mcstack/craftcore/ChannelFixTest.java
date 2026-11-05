package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChannelFixTest {
  /** Stand-in for CraftPlayer: the same public method an incoming REGISTER calls. */
  public static final class FakeCraftPlayer {
    final Set<String> channels = new HashSet<>();
    public void addChannel(String channel) { channels.add(channel); }
  }

  @Test void aMissingProxyChannelIsRegisteredSoSwitchesAreNotDropped() {
    FakeCraftPlayer p = new FakeCraftPlayer();
    assertEquals(ChannelFix.Outcome.ADDED, ChannelFix.ensure(p, p.channels, "bungeecord:main"));
    assertTrue(p.channels.contains("bungeecord:main"));
    assertEquals(ChannelFix.Outcome.ALREADY, ChannelFix.ensure(p, p.channels, "bungeecord:main"));
  }

  /** Stand-in for Paper 26.x CraftPlayer: channels() is the connection's live set. */
  public static final class FakePaperPlayer {
    final Set<String> live = new HashSet<>();
    public Set<String> channels() { return live; }
  }

  @Test void paper26LiveChannelSetIsUsed() {
    FakePaperPlayer p = new FakePaperPlayer();
    assertEquals(ChannelFix.Outcome.ADDED, ChannelFix.ensure(p, Set.copyOf(p.live), "bungeecord:main"));
    assertTrue(p.live.contains("bungeecord:main"));
  }

  @Test void withoutTheMethodItReportsUnsupportedInsteadOfThrowing() {
    assertEquals(ChannelFix.Outcome.UNSUPPORTED, ChannelFix.ensure(new Object(), Set.of(), "bungeecord:main"));
  }
}
