package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * One start per world. Every player who wants the same world attaches to the same
 * {@link CompletableFuture}, so a second click never triggers a second host start.
 *
 * <p>The coordinator only reports a world as ready after the host status ping answered
 * {@code online}. A start that fails, times out, or loses its last waiter completes the
 * future with a failure and removes the state, so the next attempt starts clean.</p>
 */
public final class VipStartCoordinator {
    public enum State { ONLINE, STARTING, FAILED }

    public record Reply(State state, String slot, String reason) {
        public static Reply online(String slot) { return new Reply(State.ONLINE, slot, ""); }
        public static Reply starting(String slot) { return new Reply(State.STARTING, slot, ""); }
        public static Reply failed(String reason) { return new Reply(State.FAILED, null, reason); }
    }

    /** Host calls, adapted from {@link VipHostClient} by the caller. */
    public interface Host {
        Reply start(String worldId);
        Reply status(String slot);
    }

    /** Runs a task later on the plugin thread. */
    public interface Scheduler {
        void later(long delayMillis, Runnable task);
    }

    public record Outcome(boolean ok, String slot, String reason) {
        public static Outcome ready(String slot) { return new Outcome(true, slot, ""); }
        public static Outcome failed(String reason) { return new Outcome(false, null, reason); }
    }

    public record Progress(String slot, long elapsedMillis, long timeoutMillis) {}

    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";
    public static final String HOST_UNAVAILABLE = "host_unavailable";
    public static final String NO_FREE_SLOT = "no_free_slot";
    public static final String RAM_BUDGET = "ram_budget";
    public static final String WORLD_NOT_FOUND = "world_not_found";
    public static final String START_FAILED = "start_failed";

    private final Host host;
    private final Scheduler scheduler;
    private final Executor executor;
    private final LongSupplier clock;
    private final long pollMillis;
    private final long timeoutMillis;
    private final int maxErrors;
    private final Map<String, Start> starts = new ConcurrentHashMap<>();

    private static final class Start {
        final String worldId;
        final CompletableFuture<Outcome> future = new CompletableFuture<>();
        final Set<UUID> waiters = ConcurrentHashMap.newKeySet();
        final AtomicBoolean begun = new AtomicBoolean();
        final AtomicBoolean scheduled = new AtomicBoolean();
        volatile String slot;
        volatile long startedAt;
        volatile long deadline;
        volatile int errors;
        volatile boolean done;
        Start(String worldId) { this.worldId = worldId; }
    }

    public VipStartCoordinator(Host host, Scheduler scheduler, Executor executor, LongSupplier clock,
                               long pollMillis, long timeoutMillis, int maxErrors) {
        this.host = host;
        this.scheduler = scheduler;
        this.executor = executor;
        this.clock = clock;
        this.pollMillis = Math.max(1, pollMillis);
        this.timeoutMillis = Math.max(1, timeoutMillis);
        this.maxErrors = Math.max(1, maxErrors);
    }

    /**
     * Attaches a player to the start of that world and returns the shared future.
     * The first caller starts the world; every later caller only waits.
     */
    public CompletableFuture<Outcome> join(String worldId, UUID player) {
        Start start = starts.computeIfAbsent(worldId, Start::new);
        start.waiters.add(player);
        if (start.begun.compareAndSet(false, true)) {
            start.startedAt = clock.getAsLong();
            start.deadline = start.startedAt + timeoutMillis;
            executor.execute(() -> begin(start));
        }
        return start.future;
    }

    /** Removes one waiter. The last waiter cancels the start and clears the state. */
    public void leave(String worldId, UUID player) {
        Start start = starts.get(worldId);
        if (start == null) return;
        start.waiters.remove(player);
        if (start.waiters.isEmpty()) cancel(start);
    }

    public Optional<Progress> progress(String worldId) {
        Start start = starts.get(worldId);
        if (start == null || start.done || start.slot == null) return Optional.empty();
        return Optional.of(new Progress(start.slot, Math.max(0, clock.getAsLong() - start.startedAt), timeoutMillis));
    }

    public boolean active(String worldId) {
        Start start = starts.get(worldId);
        return start != null && !start.done;
    }

    public int waiters(String worldId) {
        Start start = starts.get(worldId);
        return start == null ? 0 : start.waiters.size();
    }

    public List<UUID> waitersList(String worldId) {
        Start start = starts.get(worldId);
        return start == null ? List.of() : List.copyOf(start.waiters);
    }

    public int activeCount() { return starts.size(); }

    /** Plugin disable: fail every open start and clear the map. */
    public void shutdown() {
        for (Start start : List.copyOf(starts.values())) cancel(start);
        starts.clear();
    }

    private void begin(Start start) {
        if (start.done) return;
        Reply reply;
        try { reply = host.start(start.worldId); }
        catch (RuntimeException e) { fail(start, START_FAILED); return; }
        if (reply == null) { fail(start, START_FAILED); return; }
        switch (reply.state()) {
            case ONLINE -> {
                if (blank(reply.slot())) { fail(start, START_FAILED); return; }
                start.slot = reply.slot();
                // Even a 200 from the start call is confirmed by one status ping before connecting.
                poll(start);
            }
            case STARTING -> {
                if (blank(reply.slot())) { fail(start, START_FAILED); return; }
                start.slot = reply.slot();
                if (clock.getAsLong() >= start.deadline) { fail(start, TIMEOUT); return; }
                schedule(start);
            }
            case FAILED -> fail(start, blank(reply.reason()) ? START_FAILED : reply.reason());
        }
    }

    private void poll(Start start) {
        if (start.done) return;
        Reply reply;
        try { reply = host.status(start.slot); }
        catch (RuntimeException e) { reply = null; }
        if (reply != null && reply.state() == State.ONLINE) { ready(start); return; }
        if (reply != null && reply.state() == State.STARTING) {
            start.errors = 0;
        } else if (++start.errors >= maxErrors) {
            fail(start, HOST_UNAVAILABLE);
            return;
        }
        if (clock.getAsLong() >= start.deadline) { fail(start, TIMEOUT); return; }
        schedule(start);
    }

    private void schedule(Start start) {
        if (start.done) return;
        if (!start.scheduled.compareAndSet(false, true)) return;
        scheduler.later(pollMillis, () -> {
            start.scheduled.set(false);
            poll(start);
        });
    }

    private void ready(Start start) {
        if (start.done) return;
        start.done = true;
        starts.remove(start.worldId, start);
        start.future.complete(Outcome.ready(start.slot));
    }

    private void fail(Start start, String reason) {
        if (start.done) return;
        start.done = true;
        starts.remove(start.worldId, start);
        start.future.complete(Outcome.failed(reason));
    }

    private void cancel(Start start) {
        if (start.done) return;
        start.done = true;
        starts.remove(start.worldId, start);
        start.future.complete(Outcome.failed(CANCELLED));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
