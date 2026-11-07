package de.mcstack.craftcore;
import java.util.concurrent.*;

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

  /** From a queued job: run `body` on the server thread and wait for its result (bounded). */
  static <T> T onMain(CraftCore core, Callable<T> body) throws Exception {
    return core.getServer().getScheduler().callSyncMethod(core, body).get(30, TimeUnit.SECONDS);
  }
}
