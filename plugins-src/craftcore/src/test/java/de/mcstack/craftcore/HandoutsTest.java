package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
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
  // Gatekeeper #170: a join recovery that overlaps a hand-out must not act on a marker snapshot taken before it.
  @Test void recoveryIsQueuedBehindAHandoutInProgress() throws Exception {
    ExecutorService pool = Executors.newCachedThreadPool();
    try (Connection c = db("g1")) {
      PlayerQueue q = new PlayerQueue(pool);
      Set<String> playerFile = ConcurrentHashMap.newKeySet();           // the player's saved markers
      CountDownLatch inHandout = new CountDownLatch(1), release = new CountDownLatch(1);
      Object[] dropped = new Object[1];
      CompletableFuture<Void> handout = q.submit(P, () -> { try {
        assertTrue(Handouts.reserve(c, "d1", P, 5, "survival")); inHandout.countDown(); release.await();
        playerFile.add(Handouts.itemMark("d1", 5));                   // items + marker saved in one write
        assertTrue(Handouts.finish(c, "d1", 5)); } catch (Exception e) { throw new CompletionException(e); } });
      inHandout.await();
      // the join recovery reads the markers in its own turn, after the hand-out
      CompletableFuture<Void> recovery = q.submit(P, () -> { try { dropped[0] = Handouts.reconcile(c, P, "survival", Set.copyOf(playerFile)); } catch (Exception e) { throw new CompletionException(e); } });
      assertFalse(recovery.isDone(), "recovery waits for the running hand-out");
      release.countDown(); handout.get(5, TimeUnit.SECONDS); recovery.get(5, TimeUnit.SECONDS);
      assertEquals(List.of("geliefert:5:NBT"), rows(c), "delivered once, not reopened");
      assertEquals(Set.of(Handouts.itemMark("d1", 5)), dropped[0]);
    } finally { pool.shutdownNow(); }
  }
  @Test void aStalledRecoveryDoesNotBlockTheServerThreadOrOtherPlayers() throws Exception {
    ExecutorService pool = Executors.newCachedThreadPool(), serverThread = Executors.newSingleThreadExecutor();
    try {
      PlayerQueue q = new PlayerQueue(pool);
      CountDownLatch stalled = new CountDownLatch(1), dbBack = new CountDownLatch(1);
      CompletableFuture<Void> recovery = q.submit(P, () -> { stalled.countDown(); try { dbBack.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });   // DB lock wait / slow DB
      stalled.await();
      assertEquals("tick", serverThread.submit(() -> "tick").get(1, TimeUnit.SECONDS), "the server thread keeps running");
      CompletableFuture<Void> other = q.submit("other-player", () -> {});
      other.get(1, TimeUnit.SECONDS);                                   // another player's hand-out isn't held up
      boolean[] ran = new boolean[1];
      CompletableFuture<Void> next = q.submit(P, () -> ran[0] = true);
      assertFalse(next.isDone() || ran[0], "the same player's next job waits for the stalled one");
      dbBack.countDown(); recovery.get(5, TimeUnit.SECONDS); next.get(5, TimeUnit.SECONDS);
      assertTrue(ran[0]);
      CompletableFuture<Void> failing = q.submit(P, () -> { throw new IllegalStateException("db down"); });
      CompletableFuture<Void> after = q.submit(P, () -> {});
      after.get(5, TimeUnit.SECONDS);                                   // a failed job doesn't block the queue
      assertTrue(failing.isCompletedExceptionally());
    } finally { pool.shutdownNow(); serverThread.shutdownNow(); }
  }
  @Test void withoutTheQueueAStaleSnapshotWouldReopenAGivenHandout() throws Exception {
    try (Connection c = db("g2")) {
      Set<String> stale = Set.of();                                     // join captured the markers first ...
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));         // ... then /abholen reserved, gave and saved
      Handouts.reconcile(c, P, "survival", stale);                      // ... and the delayed recovery ran
      assertFalse(Handouts.finish(c, "d1", 5), "the hazard the queue prevents: the given row was reopened");
      assertEquals(List.of("offen:5:NBT"), rows(c));
    }
  }
  @Test void overlappingXpPaymentsPayOnce() throws Exception {
    try (Connection c = db("g3")) {
      Set<String> playerFile = new HashSet<>();
      List<long[]> owed = List.of(new long[]{7, 30});
      // grave hand-out pays, saves the marker; the DB update is still pending
      List<long[]> first = Handouts.xpToPay(c, owed, Set.copyOf(playerFile));
      assertEquals(1, first.size()); playerFile.add(Handouts.xpMark(7));
      // a join recovery (or a second hand-out) with the same stale list, in the next gate section: nothing to pay
      assertTrue(Handouts.xpToPay(c, owed, Set.copyOf(playerFile)).isEmpty());
      assertTrue(Handouts.reconcileXp(c, "survival", P, Set.copyOf(playerFile)).getKey().isEmpty(), "recovery marks it paid instead");
      playerFile.clear();                                               // marker dropped after the DB update
      assertTrue(Handouts.xpToPay(c, owed, Set.copyOf(playerFile)).isEmpty(), "paid in the DB: never again");
    }
  }
  // Round 6 (Gatekeeper #170): a main-thread hop that runs after its timeout must never give late.
  private static <T> java.util.function.Function<Callable<T>, Future<T>> on(ExecutorService main) { return main::submit; }

  @Test void stalledItemHopIsCancelledAndTheItemsAreGivenOnce() throws Exception {
    ExecutorService main = Executors.newSingleThreadExecutor();
    try (Connection c = db("r6a")) {
      Set<String> playerFile = ConcurrentHashMap.newKeySet(); int[] given = {0};
      Callable<Integer> give = () -> { given[0] += 5; playerFile.add(Handouts.itemMark("d1", 5)); return 5; };
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));
      CountDownLatch busy = new CountDownLatch(1);
      main.submit(() -> { busy.await(); return null; });                  // the server thread is stalled
      assertThrows(TimeoutException.class, () -> PlayerQueue.onMain(on(main), give, 50), "timed out and cancelled");
      busy.countDown(); main.submit(() -> null).get(5, TimeUnit.SECONDS); // the late callback has run now: a no-op
      assertEquals(0, given[0]); assertTrue(playerFile.isEmpty(), "nothing given, no marker");
      Handouts.reconcile(c, P, "survival", Set.copyOf(playerFile));       // recovery may reopen: nothing was given
      assertEquals(List.of("offen:5:NBT"), rows(c));
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));            // the next /abholen hands it out
      int g = PlayerQueue.onMain(on(main), give, 5_000); assertTrue(Handouts.finish(c, "d1", g));
      assertEquals(5, given[0], "given exactly once"); assertEquals(List.of("geliefert:5:NBT"), rows(c));
    } finally { main.shutdownNow(); }
  }
  @Test void itemHopAlreadyRunningAtTheTimeoutIsAwaitedNotReopened() throws Exception {
    ExecutorService main = Executors.newSingleThreadExecutor(), job = Executors.newSingleThreadExecutor();
    try (Connection c = db("r6b")) {
      Set<String> playerFile = ConcurrentHashMap.newKeySet(); int[] given = {0};
      CountDownLatch started = new CountDownLatch(1), finishGive = new CountDownLatch(1);
      Callable<Integer> give = () -> { started.countDown(); finishGive.await(); given[0] += 5; playerFile.add(Handouts.itemMark("d1", 5)); return 5; };
      assertTrue(Handouts.reserve(c, "d1", P, 5, "survival"));
      Future<Integer> hop = job.submit(() -> PlayerQueue.onMain(on(main), give, 50));
      started.await(); Thread.sleep(200);                                // well past the 50 ms timeout, callback RUNNING
      assertFalse(hop.isDone(), "the job waits for the running give instead of moving on");
      finishGive.countDown();
      int g = hop.get(5, TimeUnit.SECONDS); assertEquals(5, g);
      assertTrue(Handouts.finish(c, "d1", g));
      Handouts.reconcile(c, P, "survival", Set.copyOf(playerFile));
      assertEquals(5, given[0]); assertEquals(List.of("geliefert:5:NBT"), rows(c), "not reopened, not given twice");
    } finally { main.shutdownNow(); job.shutdownNow(); }
  }
  @Test void stalledXpHopIsCancelledAndTheXpIsPaidOnce() throws Exception {
    ExecutorService main = Executors.newSingleThreadExecutor();
    try (Connection c = db("r6c")) {
      Set<String> playerFile = ConcurrentHashMap.newKeySet(); int[] xp = {0};
      List<long[]> owed = List.of(new long[]{7, 30});
      Callable<Boolean> give = () -> { xp[0] += 30; playerFile.add(Handouts.xpMark(7)); return true; };
      assertEquals(1, Handouts.xpToPay(c, owed, Set.copyOf(playerFile)).size());
      CountDownLatch busy = new CountDownLatch(1);
      main.submit(() -> { busy.await(); return null; });
      assertThrows(TimeoutException.class, () -> PlayerQueue.onMain(on(main), give, 50));
      busy.countDown(); main.submit(() -> null).get(5, TimeUnit.SECONDS);
      assertEquals(0, xp[0], "late callback gave nothing");
      // the next join: still owed, no marker -> paid now, then marked paid
      var r = Handouts.reconcileXp(c, "survival", P, Set.copyOf(playerFile));
      assertEquals(1, r.getKey().size());
      assertTrue(PlayerQueue.onMain(on(main), give, 5_000)); assertTrue(GraveStore.xpPaid(c, 7));
      assertTrue(Handouts.reconcileXp(c, "survival", P, Set.copyOf(playerFile)).getKey().isEmpty());
      assertEquals(30, xp[0], "XP paid exactly once");
    } finally { main.shutdownNow(); }
  }
  @Test void xpHopRunningAtTheTimeoutIsAwaitedAndNotPaidAgain() throws Exception {
    ExecutorService main = Executors.newSingleThreadExecutor(), job = Executors.newSingleThreadExecutor();
    try (Connection c = db("r6d")) {
      Set<String> playerFile = ConcurrentHashMap.newKeySet(); int[] xp = {0};
      CountDownLatch started = new CountDownLatch(1), done = new CountDownLatch(1);
      Callable<Boolean> give = () -> { started.countDown(); done.await(); xp[0] += 30; playerFile.add(Handouts.xpMark(7)); return true; };
      Future<Boolean> hop = job.submit(() -> PlayerQueue.onMain(on(main), give, 50));
      started.await(); Thread.sleep(200); done.countDown();
      assertTrue(hop.get(5, TimeUnit.SECONDS));
      // the DB update failed (not marked paid): the next join sees the saved marker and does not pay again
      assertTrue(Handouts.reconcileXp(c, "survival", P, Set.copyOf(playerFile)).getKey().isEmpty());
      assertTrue(Handouts.xpToPay(c, List.of(new long[]{7, 30}), Set.copyOf(playerFile)).isEmpty());
      assertEquals(30, xp[0]);
    } finally { main.shutdownNow(); job.shutdownNow(); }
  }
  @Test void markersRoundTrip() {
    Set<String> m = Handouts.parse(Handouts.format(new LinkedHashSet<>(List.of(Handouts.itemMark("a-b", 3), Handouts.xpMark(9)))));
    assertEquals(3, Handouts.given(m, "a-b")); assertEquals(-1, Handouts.given(m, "a")); assertTrue(m.contains("x:9"));
    assertTrue(Handouts.parse(null).isEmpty()); assertTrue(Handouts.parse("").isEmpty());
  }
}
