package de.mcstack.craftcore;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coordinates slot ownership and delivers transaction outcomes on the main thread. */
final class SellStackFlow {
  private final Set<String> slots = new HashSet<>();
  private final Consumer<Runnable> async, main;
  SellStackFlow(Consumer<Runnable> async, Consumer<Runnable> main) { this.async=async; this.main=main; }
  boolean busy(String slot) { return slots.contains(slot); }
  <T> boolean start(String slot, Runnable take, Supplier<T> transaction, Consumer<T> complete) {
    if (!slots.add(slot)) return false;
    try {
      take.run();
      async.accept(() -> {
        T result=transaction.get();
        main.accept(() -> { try { complete.accept(result); } finally { slots.remove(slot); } });
      });
      return true;
    } catch (RuntimeException e) { slots.remove(slot); throw e; }
  }
  static void resolve(SellTransaction.CommitStatus status, Runnable committed, Runnable refund, Runnable unknown) {
    switch (status) {
      case COMMITTED -> committed.run();
      case NOT_COMMITTED -> refund.run();
      case UNKNOWN -> unknown.run();
    }
  }
}
