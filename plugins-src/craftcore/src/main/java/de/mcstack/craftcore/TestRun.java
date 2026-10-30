package de.mcstack.craftcore;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Console/RCON test credit. Off by default, and only the fake c0de accounts can receive CP. */
final class TestRun {
  static final long MAX_PER_CALL = 1_000_000L;
  static final long MAX_TOTAL = 1_000_000L;
  enum Sender { PLAYER, CONSOLE, RCON, OTHER }
  record Result(boolean credit, String reply, String uuid, long cents, String key) {}

  static boolean enabled(Boolean configured) { return Boolean.TRUE.equals(configured); }

  static Result decide(boolean enabled, Sender sender, String rawUuid, long cents, String key) {
    if (sender != Sender.CONSOLE && sender != Sender.RCON) return refuse("ERR INVALID");
    if (!enabled) return refuse("ERR DISABLED");
    String uuid = fakeUuid(rawUuid);
    if (uuid == null || cents <= 0 || cents > MAX_PER_CALL || !validKey(key)) return refuse("ERR INVALID");
    return new Result(true, "OK", uuid, cents, key);
  }

  static void prepare(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS testrun_credits(idem_key VARBINARY(64) PRIMARY KEY, uuid VARCHAR(36) NOT NULL, cents BIGINT NOT NULL)");
    }
  }

  static boolean apply(Connection c, Result result) throws SQLException {
    if (!result.credit() || result.cents() <= 0 || result.cents() > MAX_PER_CALL || fakeUuid(result.uuid()) == null || !validKey(result.key())) return false;
    byte[] key = result.key().getBytes(StandardCharsets.US_ASCII);
    int inserted;
    try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO testrun_credits(idem_key,uuid,cents) VALUES(?,?,?)")) {
      ps.setBytes(1, key); ps.setString(2, result.uuid()); ps.setLong(3, result.cents()); inserted = ps.executeUpdate();
    }
    if (inserted == 0) {
      try (PreparedStatement ps = c.prepareStatement("SELECT uuid,cents FROM testrun_credits WHERE idem_key=?")) {
        ps.setBytes(1, key);
        try (ResultSet r = ps.executeQuery()) {
          return r.next() && result.uuid().equals(r.getString(1)) && result.cents() == r.getLong(2);
        }
      }
    }
    // Lock this account's credit rows so two overlapping applies cannot both pass the cap.
    // CraftCore.tx also holds GET_LOCK('craftcore-write'), which serializes the async testrun task with every other economy write.
    long total = 0;
    try (PreparedStatement ps = c.prepareStatement("SELECT cents FROM testrun_credits WHERE uuid=? FOR UPDATE")) {
      ps.setString(1, result.uuid());
      try (ResultSet r = ps.executeQuery()) { while (r.next()) total += r.getLong(1); }
    }
    if (total > MAX_TOTAL || !EconomyStore.change(c, result.uuid(), result.cents())) {
      try (PreparedStatement ps = c.prepareStatement("DELETE FROM testrun_credits WHERE idem_key=?")) { ps.setBytes(1, key); ps.executeUpdate(); }
      return false;
    }
    return true;
  }

  record Dispatch(String line, boolean start) {}

  static String cacheId(Result result) { return "testrun\n" + result.key() + "\n" + result.uuid() + "\n" + result.cents(); }

  /** Immediate refusal, a stored OK/ERR, or PENDING while the credit runs asynchronously. */
  static Dispatch dispatch(Result result, String stored) {
    if (!result.credit()) return new Dispatch(result.reply(), false);
    if (stored != null && (stored.equals("OK") || stored.startsWith("OK ") || stored.startsWith("ERR "))) return new Dispatch(stored, false);
    return new Dispatch("PENDING " + result.key(), true);
  }

  private static Result refuse(String reply) { return new Result(false, reply, null, 0, null); }

  private static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9_-]{1,64}"); }

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
