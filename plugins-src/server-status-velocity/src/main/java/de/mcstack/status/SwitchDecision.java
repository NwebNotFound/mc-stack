package de.mcstack.status;

/**
 * What the proxy does with one queued switch request (table proxy_switch_requests, written by CraftCore).
 * Backends normally switch players with a bungeecord:main plugin message; when Paper drops that message (05.11.2026:
 * portal, /lobby and NPCs did nothing while Velocity's own /server worked), this queue still moves the player.
 */
public final class SwitchDecision {
  public enum Action { WAIT, CONNECT, ALREADY_THERE, OFFLINE, UNKNOWN_SERVER, EXPIRED }

  /** The plugin-message path gets this long first; most switches are done before the queue acts. */
  public static final long GRACE_MS = 4_000; // a normal backend login takes about 3 s
  /** Older requests are dropped: the player has long given up or done something else. */
  public static final long MAX_AGE_MS = 15_000;

  private SwitchDecision() {}

  static boolean belongsToSession(long createdAt, Long loginAt, String source, String current) {
    return loginAt != null && createdAt >= loginAt && source != null && source.equalsIgnoreCase(current);
  }

  public static Action decide(long createdAt, long now, boolean playerOnline, String currentServer, String target, boolean targetExists) {
    long age = now - createdAt;
    if (age >= MAX_AGE_MS) return Action.EXPIRED;
    if (age < GRACE_MS) return Action.WAIT;
    if (!playerOnline) return Action.OFFLINE;
    if (!targetExists) return Action.UNKNOWN_SERVER;
    if (target.equalsIgnoreCase(currentServer)) return Action.ALREADY_THERE;
    return Action.CONNECT;
  }
}
