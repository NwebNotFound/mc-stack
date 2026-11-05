package de.mcstack.status;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Proxy receipt is the ordering boundary. No delayed queue, database read, or check-to-dispatch gap. */
final class SwitchRouter {
  private record State(Object session, Object backend, long revision) {}
  private final Map<UUID, State> latest = new HashMap<>();

  synchronized boolean dispatch(UUID id, Object session, Object backend, long revision, BooleanSupplier currentBackend, Runnable connect) {
    if (!currentBackend.getAsBoolean()) return false;
    State previous = latest.get(id);
    if (previous != null && previous.session == session && previous.backend == backend && revision < previous.revision) return false;
    latest.put(id, new State(session, backend, revision));
    // connect submits a nonblocking Velocity connection request inside the same critical section as acceptance.
    connect.run();
    return true;
  }

  synchronized void forget(UUID id, Object session) {
    State state = latest.get(id);
    if (state != null && state.session == session) latest.remove(id);
  }
}
