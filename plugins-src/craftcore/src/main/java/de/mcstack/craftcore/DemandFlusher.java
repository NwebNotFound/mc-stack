package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Coordinates the periodic demand flush with shutdown so accepted demand is never lost or counted twice.
 * <ul>
 *   <li>Every drained batch gets an id. The writer applies a batch id at most once (idempotency row in the same
 *       transaction), so writing the same batch again after an unknown outcome is safe.</li>
 *   <li>One lock: only its holder drains and writes; nobody clears another thread's state.</li>
 *   <li>The batch being written is published as {@code inFlight}. If {@link #stop} times out waiting for it, stop
 *       journals that batch together with the ledger to disk <em>before</em> returning, so the process may exit at
 *       once. If the blocked write commits later anyway, the replay skips the id.</li>
 * </ul>
 */
final class DemandFlusher {
  record Batch(String id, Map<String, Long> delta) {}
  interface Writer { void write(Batch batch) throws Exception; }
  interface Journal { void save(List<Batch> batches) throws Exception; }

  private final DemandLedger ledger;
  private final Writer writer;
  private final Journal journal;
  private final Consumer<String> warn;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile boolean stopping;
  private volatile Batch inFlight;
  /** A batch whose write failed. Retried with the same id, because a failed commit ack can still mean it landed. */
  private volatile Batch retry;
  /**
   * Guards the handoff of demand between the ledger, {@code inFlight} and {@code retry}. Drain + publish happen in one
   * step under it, and stop reads all three under it, so there is no moment in which a batch is in none of them.
   */
  private final Object handoff = new Object();
  /** Test hook: runs inside the handoff, after the drain and before the batch is published as in flight. */
  Runnable afterDrain = () -> {};

  DemandFlusher(DemandLedger ledger, Writer writer, Journal journal, Consumer<String> warn) {
    this.ledger = ledger; this.writer = writer; this.journal = journal; this.warn = warn;
  }

  static Batch batch(Map<String, Long> delta) { return new Batch(UUID.randomUUID().toString(), Map.copyOf(delta)); }

  /** Periodic flush. Skips when another flush runs or shutdown has begun. */
  boolean flush() {
    if (stopping || (retry == null && ledger.isEmpty()) || !lock.tryLock()) return false;
    try {
      Batch b;
      synchronized (handoff) {
        if (stopping) return false;
        b = retry;
        if (b == null) {
          Map<String, Long> drained = ledger.drain();
          if (drained.isEmpty()) return true;
          b = batch(drained);
        }
        afterDrain.run();
        inFlight = b;
      }
      boolean ok = false;
      try { writer.write(b); ok = true; }
      catch (Exception e) { warn.accept("Craft-Nachfrage: " + e.getMessage()); }
      finally {
        synchronized (handoff) {
          retry = ok ? null : b;
          inFlight = null;
        }
      }
      return true;
    } finally { lock.unlock(); }
  }

  /**
   * Orderly shutdown. Waits up to {@code waitMs} for an in-flight flush. With the lock it writes what is pending
   * (two attempts) and journals the rest; without it, it journals the ledger plus the in-flight batch.
   * Returns only after everything accepted is either committed or on disk.
   */
  void stop(long waitMs) {
    synchronized (handoff) { stopping = true; }
    boolean owned = false;
    try { owned = lock.tryLock(waitMs, TimeUnit.MILLISECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    if (!owned) {
      warn.accept("Craft-Nachfrage: laufender Flush beim Stopp blockiert, wird auf Platte gesichert");
      List<Batch> rest = new ArrayList<>();
      synchronized (handoff) {
        Batch pending = inFlight, failed = retry;
        if (pending != null) rest.add(pending);
        if (failed != null && failed != pending) rest.add(failed);
        Map<String, Long> left = ledger.drain();
        if (!left.isEmpty()) rest.add(batch(left));
      }
      save(rest);
      return;
    }
    try {
      List<Batch> todo = new ArrayList<>();
      if (retry != null) todo.add(retry);
      Map<String, Long> drained = ledger.drain();
      if (!drained.isEmpty()) todo.add(batch(drained));
      List<Batch> unsaved = new ArrayList<>();
      for (Batch b : todo) {
        boolean ok = false;
        for (int attempt = 1; attempt <= 2 && !ok; attempt++) {
          try { writer.write(b); ok = true; }
          catch (Exception e) { warn.accept("Craft-Nachfrage beim Stopp (" + attempt + "/2): " + e.getMessage()); }
        }
        if (!ok) unsaved.add(b);
      }
      retry = null;
      save(unsaved);
    } finally { lock.unlock(); }
  }

  private void save(List<Batch> batches) {
    if (batches.isEmpty()) return;
    try { journal.save(batches); }
    catch (Exception e) { warn.accept("Craft-Nachfrage verloren (" + batches.size() + " Batches): " + e.getMessage()); }
  }
}
