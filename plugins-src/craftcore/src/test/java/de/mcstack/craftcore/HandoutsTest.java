package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Gatekeeper #170 re-review: each crash/failure window of the inbox and grave-XP hand-out, on H2. */
class HandoutsTest {
  static final String P = "11111111-2222-3333-4444-555555555555";
  private Connection db(String name) throws Exception {
    Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36), item_nbt CLOB, amount INT, source VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, status VARCHAR(16), handout_server VARCHAR(32))");
      s.execute("CREATE TABLE order_fulfillment(request_id VARCHAR(64) PRIMARY KEY, delivery_id VARCHAR(36), status VARCHAR(16), updated_at TIMESTAMP)");
      s.execute("CREATE TABLE order_fulfillment_parts(request_id VARCHAR(64), delivery_id VARCHAR(36), partial BOOLEAN)");
      s.execute("CREATE TABLE graves(id BIGINT PRIMARY KEY, server VARCHAR(32), status VARCHAR(16), xp INT, claimed_by VARCHAR(36), xp_paid BOOLEAN)");
      s.execute("INSERT INTO pending_deliveries(id,player_uuid,item_nbt,amount,source,status) VALUES('d1','" + P + "','NBT',5,'grave','offen')");
      s.execute("INSERT INTO order_fulfillment VALUES('r1','d1','ready',CURRENT_TIMESTAMP)");
      s.execute("INSERT INTO graves VALUES(7,'survival','CLAIMED',30,'" + P + "',FALSE)");
    }
    return c;
  }
  private List<String> rows(Connection c) throws Exception {
    List<String> out = new ArrayList<>();
    try (ResultSet r = c.createStatement().executeQuery("SELECT status,amount,item_nbt FROM pending_deliveries ORDER BY status,amount")) { while (r.next()) out.add(r.getString(1) + ":" + r.getInt(2) + ":" + r.getString(3)); }
    return out;
  }
  private String order(Connection c) throws Exception { try (ResultSet r = c.createStatement().executeQuery("SELECT status FROM order_fulfillment WHERE request_id='r1'")) { r.next(); return r.getString(1); } }

  @Test void crashBeforeTheSaveGivesTheItemsAgain() throws Exception {
    try (Connection c = db("w1")) {
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));
      // items added in memory, then the server dies before saveData: the saved player file has no marker
      assertEquals(Set.of(), Handouts.reconcile(c, P, "survival", Set.of()));
      assertEquals(List.of("offen:5:NBT"), rows(c), "back in the inbox, nothing lost, nothing duplicated");
      assertEquals("ready", order(c));
    }
  }
  @Test void crashAfterTheSaveOnlyFinishes() throws Exception {
    try (Connection c = db("w2")) {
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));
      Set<String> marks = Set.of(Handouts.itemMark("d1", 5));
      // saveData wrote items + marker, then the DB finish failed / the server died
      Set<String> drop = Handouts.reconcile(c, P, "survival", marks);
      assertEquals(List.of("geliefert:5:NBT"), rows(c), "settled as delivered, not handed out again");
      assertEquals("collected", order(c));
      assertEquals(marks, drop, "marker can go");
      assertEquals(marks, Handouts.reconcile(c, P, "survival", marks), "idempotent: a second join changes nothing");
      assertEquals(List.of("geliefert:5:NBT"), rows(c));
    }
  }
  @Test void partlyGivenRestGoesBackToTheInbox() throws Exception {
    try (Connection c = db("w3")) {
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));
      Handouts.reconcile(c, P, "survival", Set.of(Handouts.itemMark("d1", 3)));
      assertEquals(List.of("geliefert:3:NBT", "offen:2:NBT"), rows(c));
      try (ResultSet r = c.createStatement().executeQuery("SELECT delivery_id FROM order_fulfillment WHERE request_id='r1'")) { r.next(); assertNotEquals("d1", r.getString(1), "order follows the rest"); }
    }
  }
  @Test void reserveSplitsTheRestAndOtherServersDoNotTouchIt() throws Exception {
    try (Connection c = db("w4")) {
      assertTrue(Handouts.reserve(c, "d1", P, 2, "survival"));
      assertEquals(List.of("offen:3:NBT", "uebergabe:2:NBT"), rows(c));
      assertFalse(Handouts.reserve(c, "d1", P, 2, "survival"), "a reserved row can't be reserved twice");
      // the marker lives in survival's player file: lobby must not settle it
      assertEquals(Set.of(), Handouts.reconcile(c, P, "lobby", Set.of()));
      assertEquals(List.of("offen:3:NBT", "uebergabe:2:NBT"), rows(c));
    }
  }
  @Test void normalPathFinishesOnce() throws Exception {
    try (Connection c = db("w5")) {
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));
      assertTrue(Handouts.finish(c, "d1", 5));
      assertFalse(Handouts.finish(c, "d1", 5), "finish is idempotent");
      assertEquals(Set.of(Handouts.itemMark("d1", 5)), Handouts.reconcile(c, P, "survival", Set.of(Handouts.itemMark("d1", 5))), "leftover marker dropped");
    }
  }
  @Test void xpGivenAndSavedButNotMarkedIsNotPaidAgain() throws Exception {
    try (Connection c = db("x1")) {
      // giveExp + "x:7" marker saved, then xpPaid failed (DB down) or the server died
      var r = Handouts.reconcileXp(c, "survival", P, Set.of(Handouts.xpMark(7)));
      assertTrue(r.getKey().isEmpty(), "no second payment");
      assertEquals(Set.of(Handouts.xpMark(7)), r.getValue());
      assertTrue(GraveStore.unpaidXp(c, "survival", P).isEmpty(), "now marked paid");
    }
  }
  @Test void xpNeverSavedIsPaidOnJoin() throws Exception {
    try (Connection c = db("x2")) {
      // disconnect or crash before the save: no marker in the player file
      var r = Handouts.reconcileXp(c, "survival", P, Set.of());
      assertEquals(1, r.getKey().size()); assertEquals(7L, r.getKey().get(0)[0]); assertEquals(30L, r.getKey().get(0)[1]);
      assertTrue(Handouts.reconcileXp(c, "lobby", P, Set.of()).getKey().isEmpty(), "graves of another server are not paid here");
      assertTrue(GraveStore.xpPaid(c, 7));
      assertEquals(Set.of(Handouts.xpMark(7)), Handouts.reconcileXp(c, "survival", P, Set.of(Handouts.xpMark(7))).getValue(), "stale marker dropped");
    }
  }
  @Test void markersRoundTrip() {
    Set<String> m = Handouts.parse(Handouts.format(new LinkedHashSet<>(List.of(Handouts.itemMark("a-b", 3), Handouts.xpMark(9)))));
    assertEquals(3, Handouts.given(m, "a-b")); assertEquals(-1, Handouts.given(m, "a")); assertTrue(m.contains("x:9"));
    assertTrue(Handouts.parse(null).isEmpty()); assertTrue(Handouts.parse("").isEmpty());
  }
}
