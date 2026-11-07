package de.mcstack.craftcore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * One job at a time per player, never on the server thread (Gatekeeper #170): /abholen, grave hand-outs, grave XP
 * and the join recovery are queued here. A job runs on an async executor and does its DB work there; player state
 * (inventory, XP, PersistentDataContainer, saveData) is touched only in short {@code onMain} hops. The server thread
 * never waits for the DB or for another job, and a stalled job only delays later jobs of the same player.
 */
final class PlayerQueue {
  private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
  private final Executor async;
  PlayerQueue(Executor async) { this.async = async; }

  /** Queue `job` behind every earlier job of `player`; a failing job doesn't block the ones after it. */
  CompletableFuture<Void> submit(String player, Runnable job) {
    CompletableFuture<Void>[] mine = new CompletableFuture[1];
    tails.compute(player, (k, prev) -> mine[0] = (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev.handle((v, e) -> null)).thenRunAsync(job, async));
    mine[0].whenComplete((v, e) -> tails.remove(player, mine[0]));   // drop the entry once the queue is idle
    return mine[0];
  }

  static final int PENDING = 0, RUNNING = 1, CANCELLED = 2;
  static volatile long hopTimeoutMs = 30_000;

  /** From a queued job: run `body` on the server thread and wait for its result (see the overload). */
  static <T> T onMain(CraftCore core, Callable<T> body) throws Exception {
    return onMain(c -> core.getServer().getScheduler().callSyncMethod(core, c), body, hopTimeoutMs);
  }

  /**
   * Run `body` through `submit` (the server thread) and wait at most `timeoutMs` (Gatekeeper #170, round 6).
   * Each hop has an atomic state PENDING -> RUNNING | CANCELLED. On timeout the job tries PENDING -> CANCELLED:
   * if that wins, the queued callback turns into a no-op when it finally runs, and this throws a TimeoutException;
   * nothing was given, so a later reconcile may reopen the reservation. If the callback already moved to RUNNING,
   * this waits for its result instead, so the job (and the player's queue) never moves on while a give is under way.
   */
  static <T> T onMain(Function<Callable<T>, Future<T>> submit, Callable<T> body, long timeoutMs) throws Exception {
    AtomicInteger state = new AtomicInteger(PENDING);
    Future<T> f = submit.apply(() -> state.compareAndSet(PENDING, RUNNING) ? body.call() : null);
    try { return f.get(timeoutMs, TimeUnit.MILLISECONDS); }
    catch (TimeoutException te) {
      if (state.compareAndSet(PENDING, CANCELLED)) throw new TimeoutException("server thread busy: hand-out step cancelled, nothing given");
      return f.get();   // already running on the server thread: wait for it, never act as if it didn't happen
    }
  }
}
