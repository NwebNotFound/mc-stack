package de.mcstack.craftcore;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Starts the database-backed modules exactly once, as soon as the database answers. Before this, a database that was
 * still down at boot (e.g. right after a backup) made {@code onEnable} disable the whole plugin: portals, /lobby, the
 * VIP menu and every server switch silently did nothing until the next restart (Nils' report, 05.11.2026).
 */
final class StartupGate {
  private final BooleanSupplier probe;
  private final Runnable start;
  private final AtomicBoolean started = new AtomicBoolean();
  private int attempts;

  StartupGate(BooleanSupplier probe, Runnable start) { this.probe = probe; this.start = start; }

  /** One attempt. Returns true once the modules are started (now or earlier). Safe to call repeatedly. */
  boolean attempt() {
    if (started.get()) return true;
    attempts++;
    if (!probe.getAsBoolean()) return false;
    if (started.compareAndSet(false, true)) start.run();
    return true;
  }

  boolean started() { return started.get(); }
  int attempts() { return attempts; }
}
