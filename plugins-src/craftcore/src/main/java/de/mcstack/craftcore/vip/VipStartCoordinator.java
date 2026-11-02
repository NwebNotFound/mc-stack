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
 *
 * <p>Every state transition happens exactly once and is reported through {@link Events}, so
 * the database is written once per start instead of once per waiting player.</p>
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

    /** Runs a task later. The plugin passes an asynchronous scheduler: polling does HTTP. */
    public interface Scheduler {
        void later(long delayMillis, Runnable task);
    }

    /** One callback per finished start. Implementations may block; they run off the server thread. */
    public interface Events {
        default void ready(String worldId, String slot) { }
        /** Exactly once per start that ends without a slot. {@code began} tells whether the host was asked. */
        default void finished(String worldId, String reason, String slot, boolean began) { }
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
    private final Events events;
    private final Map<String, Start> starts = new ConcurrentHashMap<>();

    private static final class Start {
        final String worldId;
        final CompletableFuture<Outcome> future = new CompletableFuture<>();
        final Set<UUID> waiters = ConcurrentHashMap.newKeySet();
        final AtomicBoolean begun = new AtomicBoolean();
        final AtomicBoolean scheduled = new AtomicBoolean();
        final AtomicBoolean done = new AtomicBoolean();
        volatile String slot;
        volatile long startedAt;
        volatile long deadline;
        volatile int errors;
        Start(String worldId) { this.worldId = worldId; }
        boolean finished() { return done.get(); }
    }

    public VipStartCoordinator(Host host, Scheduler scheduler, Executor executor, LongSupplier clock,
                               long pollMillis, long timeoutMillis, int maxErrors) {
        this(host, scheduler, executor, clock, pollMillis, timeoutMillis, maxErrors, new Events() { });
    }

    public VipStartCoordinator(Host host, Scheduler scheduler, Executor executor, LongSupplier clock,
                               long pollMillis, long timeoutMillis, int maxErrors, Events events) {
        this.host = host;
        this.scheduler = scheduler;
        this.executor = executor;
        this.clock = clock;
        this.pollMillis = Math.max(1, pollMillis);
        this.timeoutMillis = Math.max(1, timeoutMillis);
        this.maxErrors = Math.max(1, maxErrors);
        this.events = events == null ? new Events() { } : events;
    }

    /**
     * Attaches a player to the start of that world and returns the shared future.
     * The first caller starts the world; every later caller only waits.
     *
     * <p>Adding the waiter and deciding whether this call begins the start happen inside one
     * {@code compute}, so a concurrent {@link #leave} can neither orphan a waiter on a
     * cancelled future nor cancel a start that just received one.</p>
     */
    public CompletableFuture<Outcome> join(String worldId, UUID player) {
        AtomicBoolean begins = new AtomicBoolean();
        Start start = starts.compute(worldId, (id, existing) -> {
            Start next = existing == null || existing.finished() ? new Start(id) : existing;
            next.waiters.add(player);
            if (next.begun.compareAndSet(false, true)) {
                next.startedAt = clock.getAsLong();
                next.deadline = next.startedAt + timeoutMillis;
                begins.set(true);
            }
            return next;
        });
        if (begins.get()) executor.execute(() -> begin(start));
        return start.future;
    }

    /** Removes one waiter. The last waiter cancels the start and clears the state. */
    public void leave(String worldId, UUID player) {
        Start[] cancelled = new Start[1];
        starts.computeIfPresent(worldId, (id, start) -> {
            start.waiters.remove(player);
            if (start.finished()) return null;
            if (!start.waiters.isEmpty()) return start;
            cancelled[0] = start;
            return null;
        });
        if (cancelled[0] != null) end(cancelled[0], CANCELLED, true);
    }

    public Optional<Progress> progress(String worldId) {
        Start start = starts.get(worldId);
        if (start == null || start.finished() || start.slot == null) return Optional.empty();
        return Optional.of(new Progress(start.slot, Math.max(0, clock.getAsLong() - start.startedAt), timeoutMillis));
    }

    public boolean active(String worldId) {
        Start start = starts.get(worldId);
        return start != null && !start.finished();
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

    /** Plugin disable: fail every open start without touching the host or the database. */
    public void shutdown() {
        for (Start start : List.copyOf(starts.values())) end(start, CANCELLED, false);
        starts.clear();
    }

    private void begin(Start start) {
        if (start.finished()) return;
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
        if (start.finished()) return;
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
        if (start.finished()) return;
        if (!start.scheduled.compareAndSet(false, true)) return;
        scheduler.later(pollMillis, () -> {
            start.scheduled.set(false);
            poll(start);
        });
    }

    private void ready(Start start) {
        if (!start.done.compareAndSet(false, true)) return;
        starts.remove(start.worldId, start);
        notify(() -> events.ready(start.worldId, start.slot));
        start.future.complete(Outcome.ready(start.slot));
    }

    private void fail(Start start, String reason) { end(start, reason, true); }

    private void end(Start start, String reason, boolean report) {
        if (!start.done.compareAndSet(false, true)) return;
        starts.remove(start.worldId, start);
        if (report) notify(() -> events.finished(start.worldId, reason, start.slot, start.begun.get()));
        start.future.complete(Outcome.failed(reason));
    }

    private static void notify(Runnable callback) {
        try { callback.run(); } catch (RuntimeException ignored) { }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
