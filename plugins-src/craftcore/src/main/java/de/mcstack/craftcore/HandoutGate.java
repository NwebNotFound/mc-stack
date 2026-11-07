package de.mcstack.craftcore;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One hand-out or recovery at a time per player (Gatekeeper #170): /abholen, a grave's items and XP, and the join
 * recovery all run their whole sequence (marker capture, DB reserve/reconcile, give, marker, save, finish) inside
 * {@link #run}. A recovery can then never act on a marker snapshot taken before a hand-out it overlaps with.
 * All callers are on the main thread today; the lock keeps it correct if that ever changes and makes the
 * interleaving testable. Reentrant: a grave hand-out may call /abholen's collect inside its own section.
 */
final class HandoutGate {
  private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
  static <T> T run(String player, Callable<T> body) throws Exception {
    ReentrantLock l = LOCKS.computeIfAbsent(player, k -> new ReentrantLock());
    l.lock();
    try { return body.call(); } finally { l.unlock(); }
  }
}
