package de.mcstack.craftcore;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Paper's {@code sendPluginMessage} silently drops a message on a channel the connection has not registered. Velocity
 * normally registers {@code bungeecord:main} for each backend connection; if that register never arrives, every
 * plugin-driven switch (portal, /lobby, NPCs, VIP) does nothing, while Velocity's own /server works (Nils, 05.11.2026).
 * Paper's player exposes that set as {@code channels()} (older CraftBukkit: {@code addChannel(String)}); we add the
 * channel ourselves. Velocity reads bungeecord:main messages from the backend regardless.
 */
final class ChannelFix {
  enum Outcome { ALREADY, ADDED, UNSUPPORTED }

  private ChannelFix() {}

  static Outcome ensure(Object player, Set<String> listening, String channel) {
    if (listening != null && listening.contains(channel)) return Outcome.ALREADY;
    // Paper 26.x: CraftPlayer.channels() returns the connection's live pluginMessagerChannels set (checked by
    // sendPluginMessage). Older CraftBukkit: CraftPlayer.addChannel(String).
    try {
      Method channels = player.getClass().getMethod("channels");
      Object set = channels.invoke(player);
      if (set instanceof Set<?> live) {
        @SuppressWarnings("unchecked") Set<String> mutable = (Set<String>) live;
        mutable.add(channel);
        return Outcome.ADDED;
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {}
    try {
      Method add = player.getClass().getMethod("addChannel", String.class);
      add.invoke(player, channel);
      return Outcome.ADDED;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return Outcome.UNSUPPORTED;
    }
  }
}
