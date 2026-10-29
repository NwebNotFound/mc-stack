package de.mcstack.craftcore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class EndLockRules {
  private final Map<UUID, Long> messages = new HashMap<>();
  static boolean blocked(boolean enabled, boolean op, boolean permission) {
    return enabled && !(op && permission);
  }
  boolean shouldNotify(UUID player, long now) {
    Long last = messages.get(player);
    if (last != null && now - last < 3000) return false;
    messages.put(player, now);
    return true;
  }
  void forget(UUID player) { messages.remove(player); }
}
