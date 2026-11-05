package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DemandFlusherTest {
  static void sum(Map<String, Long> into, Map<String, Long> batch) { batch.forEach((k, v) -> into.merge(k, v, Long::sum)); }

  /** Gatekeeper round 2: a flush blocked past the shutdown wait and then failing must not lose its batch. */
  @Test void blockedFlushFailingAfterTheStopTimeoutIsSpilled() throws Exception {
    DemandLedger ledger = new DemandLedger();
    CountDownLatch inWrite = new CountDownLatch(1), release = new CountDownLatch(1);
    Map<String, Long> written = new ConcurrentHashMap<>(), spilled = new ConcurrentHashMap<>();
    DemandFlusher f = new DemandFlusher(ledger, batch -> {
      inWrite.countDown();
      if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test hung");
      throw new java.sql.SQLException("db gone");
    }, batch -> sum(spilled, batch), msg -> {});
    ledger.add(Map.of("STONE", 7L));
    Thread worker = new Thread(f::flush);
    worker.start();
    assertTrue(inWrite.await(5, TimeUnit.SECONDS));
    ledger.add(Map.of("COAL", 2L)); // accepted while the flush is blocked
    f.stop(100);                     // gives up waiting
    release.countDown();             // the blocked write now fails
    worker.join(5000);
    assertFalse(worker.isAlive());
    assertEquals(Map.of("STONE", 7L, "COAL", 2L), spilled, "every accepted delta ends up in the spill");
    assertTrue(ledger.isEmpty());
    assertTrue(written.isEmpty());
  }

  @Test void stopWaitsForAnInFlightFlushThenWritesTheRest() throws Exception {
    DemandLedger ledger = new DemandLedger();
    CountDownLatch inWrite = new CountDownLatch(1), release = new CountDownLatch(1);
    List<Map<String, Long>> writes = new CopyOnWriteArrayList<>();
    Map<String, Long> spilled = new ConcurrentHashMap<>();
    DemandFlusher f = new DemandFlusher(ledger, batch -> {
      if (writes.isEmpty()) { inWrite.countDown(); release.await(10, TimeUnit.SECONDS); }
      writes.add(batch);
    }, batch -> sum(spilled, batch), msg -> {});
    ledger.add(Map.of("STONE", 1L));
    Thread worker = new Thread(f::flush);
    worker.start();
    assertTrue(inWrite.await(5, TimeUnit.SECONDS));
    ledger.add(Map.of("COAL", 3L));
    Thread stopper = new Thread(() -> f.stop(5000));
    stopper.start();
    Thread.sleep(100);
    assertTrue(stopper.isAlive(), "stop waits for the lock owner instead of clearing its state");
    release.countDown();
    worker.join(5000); stopper.join(5000);
    assertEquals(List.of(Map.of("STONE", 1L), Map.of("COAL", 3L)), writes);
    assertTrue(spilled.isEmpty());
    assertFalse(f.flush(), "no flush after shutdown");
  }

  @Test void stopSpillsWhatTheDatabaseRefusesTwice() {
    DemandLedger ledger = new DemandLedger();
    Map<String, Long> spilled = new ConcurrentHashMap<>();
    int[] calls = {0};
    DemandFlusher f = new DemandFlusher(ledger, batch -> { calls[0]++; throw new java.sql.SQLException("down"); }, batch -> sum(spilled, batch), msg -> {});
    ledger.add(Map.of("RAW_IRON", 5L, "IRON_INGOT", -5L));
    f.stop(100);
    assertEquals(2, calls[0]);
    assertEquals(Map.of("RAW_IRON", 5L, "IRON_INGOT", -5L), spilled);
  }

  @Test void failedPeriodicFlushKeepsTheBatchForTheNextOne() {
    DemandLedger ledger = new DemandLedger();
    int[] calls = {0};
    Map<String, Long> ok = new ConcurrentHashMap<>();
    DemandFlusher f = new DemandFlusher(ledger, batch -> { if (calls[0]++ == 0) throw new java.sql.SQLException("blip"); sum(ok, batch); }, batch -> fail("no spill"), msg -> {});
    ledger.add(Map.of("SAND", 4L));
    f.flush();
    ledger.add(Map.of("SAND", 1L));
    f.flush();
    assertEquals(Map.of("SAND", 5L), ok);
  }

  @Test void spillFileMergesAndReplaysOnce() throws Exception {
    File dir = Files.createTempDirectory("spill").toFile();
    DemandSpill spill = new DemandSpill(new File(dir, "scarcity-demand-spill.yml"));
    spill.save(Map.of("STONE", 3L));
    spill.save(Map.of("STONE", 2L, "COAL", -1L));
    assertEquals(Map.of("STONE", 5L, "COAL", -1L), spill.takeAll());
    assertTrue(spill.takeAll().isEmpty(), "replayed once, then moved aside");
    assertTrue(new File(dir, "scarcity-demand-spill.yml.replayed").exists(), "kept for inspection, not deleted");
  }
}
