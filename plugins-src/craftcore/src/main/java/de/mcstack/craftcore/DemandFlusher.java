package de.mcstack.craftcore;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Coordinates the periodic demand flush with shutdown so accepted demand is never lost.
 * <ul>
 *   <li>One lock: only its holder drains and writes. Nobody clears another thread's state.</li>
 *   <li>A failed write puts the batch back into the ledger.</li>
 *   <li>{@link #stop} waits for an in-flight flush. If that flush is still blocked when the wait ends, it hands the
 *       leftovers to the spill and the in-flight flush spills its own batch itself if it later fails.</li>
 *   <li>The spill (a file in the plugin folder) is replayed into the ledger on the next start.</li>
 * </ul>
 */
final class DemandFlusher {
  interface Writer { void write(Map<String, Long> batch) throws Exception; }
  interface Spill { void save(Map<String, Long> batch) throws Exception; }

  private final DemandLedger ledger;
  private final Writer writer;
  private final Spill spill;
  private final Consumer<String> warn;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile boolean stopping;

  DemandFlusher(DemandLedger ledger, Writer writer, Spill spill, Consumer<String> warn) {
    this.ledger = ledger; this.writer = writer; this.spill = spill; this.warn = warn;
  }

  /** Periodic flush. Skips when another flush runs or shutdown has begun. */
  boolean flush() {
    if (stopping || ledger.isEmpty() || !lock.tryLock()) return false;
    try {
      writeOnce();
      return true;
    } finally { lock.unlock(); }
  }

  private void writeOnce() {
    Map<String, Long> batch = ledger.drain();
    if (batch.isEmpty()) return;
    try {
      writer.write(batch);
    } catch (Exception e) {
      ledger.restore(batch);
      warn.accept("Craft-Nachfrage: " + e.getMessage());
      // Shutdown already gave up waiting for us: nobody drains the ledger any more, so keep the batch on disk.
      if (stopping) spillAll();
    }
  }

  /**
   * Orderly shutdown. Waits up to {@code waitMs} for an in-flight flush, then writes what is pending (two attempts)
   * and spills anything that still could not be written.
   */
  void stop(long waitMs) {
    stopping = true;
    boolean owned = false;
    try { owned = lock.tryLock(waitMs, TimeUnit.MILLISECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    if (!owned) {
      warn.accept("Craft-Nachfrage: laufender Flush beim Stopp blockiert, Rest wird gesichert");
      spillAll();
      return;
    }
    try {
      for (int attempt = 0; attempt < 2 && !ledger.isEmpty(); attempt++) {
        Map<String, Long> batch = ledger.drain();
        try { writer.write(batch); }
        catch (Exception e) { ledger.restore(batch); warn.accept("Craft-Nachfrage beim Stopp: " + e.getMessage()); }
      }
      spillAll();
    } finally { lock.unlock(); }
  }

  private void spillAll() {
    Map<String, Long> rest = ledger.drain();
    if (rest.isEmpty()) return;
    try { spill.save(rest); }
    catch (Exception e) { warn.accept("Craft-Nachfrage verloren (" + rest.size() + " Items): " + e.getMessage()); }
  }
}
