package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DemandFlusherTest {
  static Map<String, Long> sum(List<DemandFlusher.Batch> batches) {
    Map<String, Long> out = new ConcurrentHashMap<>();
    for (DemandFlusher.Batch b : batches) b.delta().forEach((k, v) -> out.merge(k, v, Long::sum));
    return out;
  }

  /** Round 3: stop must return with the blocked in-flight batch already on disk, so the process may exit. */
  @Test void stopTimeoutJournalsTheInFlightBatchBeforeReturning() throws Exception {
    DemandLedger ledger = new DemandLedger();
    CountDownLatch inWrite = new CountDownLatch(1), release = new CountDownLatch(1);
    List<DemandFlusher.Batch> journal = new CopyOnWriteArrayList<>();
    DemandFlusher f = new DemandFlusher(ledger, b -> {
      inWrite.countDown();
      release.await(10, TimeUnit.SECONDS);
      throw new java.sql.SQLException("db gone");
    }, journal::addAll, msg -> {});
    ledger.add(Map.of("STONE", 7L));
    Thread worker = new Thread(f::flush);
    worker.setDaemon(true);
    worker.start();
    assertTrue(inWrite.await(5, TimeUnit.SECONDS));
    ledger.add(Map.of("COAL", 2L));
    f.stop(100);
    // The worker is still blocked here; a process exit now would kill it. Everything must already be journaled.
    assertTrue(worker.isAlive());
    assertEquals(Map.of("STONE", 7L, "COAL", 2L), sum(journal));
    release.countDown();
    worker.join(5000);
    assertEquals(Map.of("STONE", 7L, "COAL", 2L), sum(journal), "a late failure adds nothing twice");
    assertTrue(ledger.isEmpty());
  }

  /** Round 4: flush paused between drain and publishing inFlight while stop times out must still be journaled. */
  @Test void pauseBetweenDrainAndInFlightIsCoveredByStop() throws Exception {
    DemandLedger ledger = new DemandLedger();
    CountDownLatch drained = new CountDownLatch(1), release = new CountDownLatch(1);
    List<DemandFlusher.Batch> journal = new CopyOnWriteArrayList<>();
    CountDownLatch neverEnds = new CountDownLatch(1);
    DemandFlusher f = new DemandFlusher(ledger, b -> neverEnds.await(10, TimeUnit.SECONDS), journal::addAll, msg -> {});
    f.afterDrain = () -> { drained.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
    ledger.add(Map.of("STONE", 9L));
    Thread worker = new Thread(f::flush);
    worker.setDaemon(true);
    worker.start();
    assertTrue(drained.await(5, TimeUnit.SECONDS), "flush paused right after the drain");
    assertTrue(ledger.isEmpty(), "the batch is out of the ledger and not yet in flight");
    Thread stopper = new Thread(() -> f.stop(100));
    stopper.start();
    Thread.sleep(300);
    assertTrue(stopper.isAlive(), "stop cannot read the state in the middle of the handoff");
    release.countDown();
    stopper.join(5000);
    assertFalse(stopper.isAlive());
    assertEquals(Map.of("STONE", 9L), sum(journal), "the drained batch is journaled before stop returns");
    neverEnds.countDown();
    worker.join(5000);
  }

  /** The blocked write commits after stop journaled it: replay skips the batch id, so nothing is counted twice. */
  @Test void lateCommitPlusReplayCountsOnce() throws Exception {
    String url = "jdbc:h2:mem:flush_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    try (Connection c = DriverManager.getConnection(url)) { SupplyStore.migrate(c); }
    DemandFlusher.Writer db = b -> { try (Connection c = DriverManager.getConnection(url)) { SupplyStore.addDemand(c, b.id(), b.delta(), Map.of(), 0); } };
    DemandLedger ledger = new DemandLedger();
    CountDownLatch inWrite = new CountDownLatch(1), release = new CountDownLatch(1);
    List<DemandFlusher.Batch> journal = new CopyOnWriteArrayList<>();
    DemandFlusher f = new DemandFlusher(ledger, b -> { inWrite.countDown(); release.await(10, TimeUnit.SECONDS); db.write(b); }, journal::addAll, msg -> {});
    ledger.add(Map.of("STONE", 5L));
    Thread worker = new Thread(f::flush);
    worker.start();
    assertTrue(inWrite.await(5, TimeUnit.SECONDS));
    f.stop(100);
    release.countDown();
    worker.join(5000);
    for (DemandFlusher.Batch b : journal) db.write(b); // next start replays the journal
    try (Connection c = DriverManager.getConnection(url)) {
      assertEquals(5, SupplyStore.load(c).get("STONE").demand(), 1e-9);
    }
  }

  @Test void failedFlushRetriesTheSameBatchId() {
    DemandLedger ledger = new DemandLedger();
    List<String> ids = new ArrayList<>();
    DemandFlusher f = new DemandFlusher(ledger, b -> { ids.add(b.id()); if (ids.size() == 1) throw new java.sql.SQLException("ack lost"); }, b -> fail("no journal"), msg -> {});
    ledger.add(Map.of("SAND", 4L));
    f.flush();
    ledger.add(Map.of("SAND", 1L));
    f.flush();
    f.flush();
    assertEquals(3, ids.size());
    assertEquals(ids.get(0), ids.get(1), "unknown outcome: retry with the same id, never under a new one");
    assertNotEquals(ids.get(1), ids.get(2));
  }

  @Test void stopWritesPendingAndJournalsWhatTheDbRefuses() {
    DemandLedger ledger = new DemandLedger();
    int[] calls = {0};
    List<DemandFlusher.Batch> journal = new CopyOnWriteArrayList<>();
    DemandFlusher f = new DemandFlusher(ledger, b -> { calls[0]++; throw new java.sql.SQLException("down"); }, journal::addAll, msg -> {});
    ledger.add(Map.of("RAW_IRON", 5L, "IRON_INGOT", -5L));
    f.stop(100);
    assertEquals(2, calls[0]);
    assertEquals(Map.of("RAW_IRON", 5L, "IRON_INGOT", -5L), sum(journal));
    assertFalse(f.flush(), "no flush after shutdown");
  }

  @Test void journalNeverReplacesOrDeletesAndRenamesOnlyAfterApply() throws Exception {
    File dir = Files.createTempDirectory("journal").toFile();
    DemandSpill spill = new DemandSpill(dir);
    DemandFlusher.Batch a = DemandFlusher.batch(Map.of("STONE", 3L)), b = DemandFlusher.batch(Map.of("COAL", -1L));
    spill.save(List.of(a));
    Thread.sleep(2);
    spill.save(List.of(b));
    assertEquals(2, dir.listFiles((d, n) -> n.endsWith(".yml")).length, "every save is a new file");
    assertThrows(IllegalStateException.class, () -> spill.replay(x -> { throw new IllegalStateException("db down"); }));
    assertEquals(2, dir.listFiles((d, n) -> n.endsWith(".yml")).length, "nothing moved aside before a commit");
    List<DemandFlusher.Batch> applied = new ArrayList<>();
    assertEquals(2, spill.replay(applied::add));
    assertEquals(Map.of("STONE", 3L, "COAL", -1L), sum(applied));
    assertEquals(0, dir.listFiles((d, n) -> n.endsWith(".yml")).length);
    assertEquals(2, dir.listFiles((d, n) -> n.endsWith(".applied")).length, "kept, not deleted");
    assertEquals(0, spill.replay(x -> fail("already applied")));
  }

  @Test void batchIdIsAppliedOnlyOnce() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:once_" + UUID.randomUUID() + ";MODE=MySQL")) {
      SupplyStore.migrate(c);
      assertTrue(SupplyStore.addDemand(c, "b1", Map.of("STONE", 4L), Map.of(), 0));
      assertFalse(SupplyStore.addDemand(c, "b1", Map.of("STONE", 4L), Map.of(), 0));
      assertEquals(4, SupplyStore.load(c).get("STONE").demand(), 1e-9);
    }
  }
}
