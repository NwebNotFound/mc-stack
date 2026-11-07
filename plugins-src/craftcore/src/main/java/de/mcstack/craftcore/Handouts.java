package de.mcstack.craftcore;
import java.sql.*;
import java.util.*;

/**
 * Crash-safe hand-out of inbox items and grave XP (Gatekeeper #170 re-review).
 *
 * The DB and the player's save file can't commit together, so the player file itself is the witness:
 *   1. DB: the inbox row is RESERVED (status 'uebergabe', handout_server = this server).
 *   2. Main thread, one tick: items/XP go to the player AND a marker ("d:<id>:<given>" / "x:<graveId>") goes
 *      into the player's PersistentDataContainer; p.saveData() writes both in the same player file.
 *   3. DB: the row is FINALISED ('geliefert', or the grave's xp_paid), then the marker is dropped.
 * A crash or DB failure anywhere leaves an exact answer for {@link #reconcile}/{@link #reconcileXp} on the
 * next join or /abholen on that server: marker present = it was given and saved -> only finalise;
 * marker absent = the save never happened -> the row goes back to 'offen' / the XP is still owed.
 * Nothing ambiguous is ever replayed automatically. All methods here are pure JDBC (tested on H2).
 */
final class Handouts {
  static final String RESERVED = "uebergabe";

  // ---- markers: a comma-separated set in one PDC string ----
  static Set<String> parse(String s) { Set<String> out = new LinkedHashSet<>(); if (s != null) for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim()); return out; }
  static String format(Set<String> m) { return String.join(",", m); }
  static String itemMark(String deliveryId, int given) { return "d:" + deliveryId + ":" + given; }
  static String xpMark(long graveId) { return "x:" + graveId; }
  /** Given amount recorded for this delivery id, or -1 when there is no marker. */
  static int given(Set<String> marks, String deliveryId) {
    String pre = "d:" + deliveryId + ":";
    for (String m : marks) if (m.startsWith(pre)) try { return Integer.parseInt(m.substring(pre.length())); } catch (NumberFormatException e) { return -1; }
    return -1;
  }

  /** Step 1: reserve `count` of an open row for a hand-out on `server`; splits off the rest as a new open row. */
  static boolean reserve(Connection c, String id, String player, int count, String server) throws SQLException {
    int amount; String data, source;
    try (PreparedStatement q = c.prepareStatement("SELECT amount,item_nbt,source FROM pending_deliveries WHERE id=? AND player_uuid=? AND status='offen'")) {
      q.setString(1, id); q.setString(2, player);
      try (ResultSet r = q.executeQuery()) { if (!r.next()) return false; amount = r.getInt(1); data = r.getString(2); source = r.getString(3); }
    }
    if (count <= 0 || count > amount) return false;
    try (PreparedStatement q = c.prepareStatement("UPDATE pending_deliveries SET status='" + RESERVED + "', amount=?, handout_server=? WHERE id=? AND player_uuid=? AND status='offen'")) {
      q.setInt(1, count); q.setString(2, server); q.setString(3, id); q.setString(4, player); if (q.executeUpdate() != 1) return false;
    }
    if (count < amount) { String rest = UUID.randomUUID().toString(); Deliveries.insertRaw(c, rest, player, data, amount - count, source); OrderFulfillment.retarget(c, id, rest); }
    return true;
  }

  /**
   * Step 3 (and recovery): `given` of the reserved amount reached the player. The row becomes 'geliefert' with that
   * amount; anything not given (inventory full) goes back as a new open row. Idempotent: false if not reserved.
   */
  static boolean finish(Connection c, String id, int given) throws SQLException {
    int reserved; String player, data, source;
    try (PreparedStatement q = c.prepareStatement("SELECT amount,player_uuid,item_nbt,source FROM pending_deliveries WHERE id=? AND status='" + RESERVED + "'")) {
      q.setString(1, id);
      try (ResultSet r = q.executeQuery()) { if (!r.next()) return false; reserved = r.getInt(1); player = r.getString(2); data = r.getString(3); source = r.getString(4); }
    }
    int g = Math.max(0, Math.min(given, reserved));
    try (PreparedStatement q = c.prepareStatement("UPDATE pending_deliveries SET status='geliefert', amount=? WHERE id=? AND status='" + RESERVED + "'")) {
      q.setInt(1, g); q.setString(2, id); if (q.executeUpdate() != 1) return false;
    }
    if (g < reserved) { String rest = UUID.randomUUID().toString(); Deliveries.insertRaw(c, rest, player, data, reserved - g, source); OrderFulfillment.retarget(c, id, rest); }
    else OrderFulfillment.collected(c, id);
    return true;
  }

  /**
   * Join / before /abholen on `server`: settle every row reserved there. With a marker the hand-out was saved, so it
   * is only finished; without one the row goes back to 'offen' (the items are not in the saved inventory).
   * Returns the markers that may be dropped (their rows are settled now or were already).
   */
  static Set<String> reconcile(Connection c, String player, String server, Set<String> marks) throws SQLException {
    List<String> reserved = new ArrayList<>();
    try (PreparedStatement q = c.prepareStatement("SELECT id FROM pending_deliveries WHERE player_uuid=? AND status='" + RESERVED + "' AND handout_server=?")) {
      q.setString(1, player); q.setString(2, server);
      try (ResultSet r = q.executeQuery()) { while (r.next()) reserved.add(r.getString(1)); }
    }
    for (String id : reserved) {
      int g = given(marks, id);
      if (g >= 0) finish(c, id, g);
      else try (PreparedStatement q = c.prepareStatement("UPDATE pending_deliveries SET status='offen', handout_server=NULL WHERE id=? AND status='" + RESERVED + "'")) { q.setString(1, id); q.executeUpdate(); }
    }
    Set<String> drop = new LinkedHashSet<>();
    for (String m : marks) {
      if (!m.startsWith("d:")) continue;
      String id = m.substring(2, m.lastIndexOf(':'));
      try (PreparedStatement q = c.prepareStatement("SELECT status FROM pending_deliveries WHERE id=?")) {
        q.setString(1, id);
        try (ResultSet r = q.executeQuery()) { if (!r.next() || !RESERVED.equals(r.getString(1))) drop.add(m); }
      }
    }
    return drop;
  }

  /**
   * In the player's queued job (PlayerQueue), with markers read in that job: of `owed`, the graves that are
   * still unpaid in the DB and not already given (an "x:" marker means given and saved, DB update pending). A stale
   * list (a grave hand-out and a join recovery for the same grave) therefore never pays twice.
   */
  static List<long[]> xpToPay(Connection c, List<long[]> owed, Set<String> marks) throws SQLException {
    List<long[]> out = new ArrayList<>();
    for (long[] g : owed) {
      if (g[1] <= 0 || marks.contains(xpMark(g[0]))) continue;
      try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM graves WHERE id=? AND status='CLAIMED' AND xp_paid=FALSE")) {
        q.setLong(1, g[0]); try (ResultSet r = q.executeQuery()) { if (r.next()) out.add(g); }
      }
    }
    return out;
  }

  /**
   * Join on `server`: graves whose XP is still unpaid. Marked ones were given and saved before the DB update
   * failed or the server died: they are only marked paid (no second payment). Returns [id, xp] still to pay,
   * plus (as the second element) the xp markers that may be dropped.
   */
  static Map.Entry<List<long[]>, Set<String>> reconcileXp(Connection c, String server, String player, Set<String> marks) throws SQLException {
    List<long[]> pay = new ArrayList<>(); Set<String> drop = new LinkedHashSet<>();
    for (long[] g : GraveStore.unpaidXp(c, server, player)) {
      if (marks.contains(xpMark(g[0]))) { GraveStore.xpPaid(c, g[0]); drop.add(xpMark(g[0])); }
      else pay.add(g);
    }
    for (String m : marks) if (m.startsWith("x:")) {
      long id; try { id = Long.parseLong(m.substring(2)); } catch (NumberFormatException e) { drop.add(m); continue; }
      try (PreparedStatement q = c.prepareStatement("SELECT xp_paid FROM graves WHERE id=?")) { q.setLong(1, id); try (ResultSet r = q.executeQuery()) { if (!r.next() || r.getBoolean(1)) drop.add(m); } }
    }
    return Map.entry(pay, drop);
  }
}
