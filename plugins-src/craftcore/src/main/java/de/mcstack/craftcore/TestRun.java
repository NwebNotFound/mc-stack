package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Console/RCON test credit. Off by default, and only the fake c0de accounts can receive CP. */
final class TestRun {
  enum Sender { PLAYER, CONSOLE, RCON, OTHER }
  record Result(boolean credit, String reply, String uuid, long cents) {}

  static boolean enabled(Boolean configured) { return Boolean.TRUE.equals(configured); }

  static Result decide(boolean enabled, Sender sender, String rawUuid, long cents) {
    if (sender != Sender.CONSOLE && sender != Sender.RCON) return refuse("ERR INVALID");
    if (!enabled) return refuse("ERR DISABLED");
    String uuid = fakeUuid(rawUuid);
    if (uuid == null || cents <= 0) return refuse("ERR INVALID");
    return new Result(true, "OK", uuid, cents);
  }

  static boolean apply(Connection c, Result result) throws SQLException {
    if (!result.credit() || result.cents() <= 0 || fakeUuid(result.uuid()) == null) return false;
    return EconomyStore.change(c, result.uuid(), result.cents());
  }

  private static Result refuse(String reply) { return new Result(false, reply, null, 0); }

  static String fakeUuid(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    try {
      String canonical = UUID.fromString(trimmed).toString();
      if (!canonical.equalsIgnoreCase(trimmed)) return null;
      if (canonical.endsWith("c0de0001") || canonical.endsWith("c0de0002")) return canonical;
    } catch (IllegalArgumentException ex) { return null; }
    return null;
  }
}
