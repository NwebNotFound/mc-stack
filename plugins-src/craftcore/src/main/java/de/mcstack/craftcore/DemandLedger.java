package de.mcstack.craftcore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects net demand deltas on the server thread and hands them to one periodic flush. A thousand smelts between two
 * flushes become one row per item in one transaction instead of a thousand transactions.
 */
final class DemandLedger {
  private final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>();

  void add(Map<String, Long> delta) {
    if (delta == null) return;
    for (var entry : delta.entrySet()) {
      Long amount = entry.getValue();
      if (entry.getKey() == null || amount == null || amount == 0) continue;
      pending.merge(entry.getKey(), amount, Long::sum);
    }
  }

  /** Removes and returns everything collected so far. A concurrent {@link #add} lands either in this drain or the next. */
  Map<String, Long> drain() {
    Map<String, Long> out = new HashMap<>();
    for (String item : pending.keySet()) {
      Long amount = pending.remove(item);
      if (amount != null && amount != 0) out.merge(item, amount, Long::sum);
    }
    return out;
  }

  /** Puts a drained batch back after a failed flush so nothing is lost. */
  void restore(Map<String, Long> batch) { add(batch); }

  boolean isEmpty() { return pending.isEmpty(); }
}
