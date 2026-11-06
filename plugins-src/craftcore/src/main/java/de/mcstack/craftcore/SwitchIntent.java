package de.mcstack.craftcore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's latest server choice. Every new switch gets a new token; a delayed resend or check only acts while
 * its token is still the latest, so it can never override a newer choice (Gatekeeper #136 round 3).
 */
final class SwitchIntent {
  private final Map<UUID, Long> latest = new ConcurrentHashMap<>();
  private long next = 0;

  synchronized long begin(UUID player) { long t = ++next; latest.put(player, t); return t; }
  long latestFor(UUID player) { return latest.getOrDefault(player, 0L); }
  boolean current(UUID player, long token) { Long t = latest.get(player); return t != null && t == token; }
  void forget(UUID player) { latest.remove(player); }
}
