package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VipStartCoordinatorTest {
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Manual scheduler = new Manual();
    private final ManualExecutor executor = new ManualExecutor();
    private final FakeHost host = new FakeHost();

    private VipStartCoordinator coordinator(long timeoutMillis, int maxErrors) {
        return new VipStartCoordinator(host, scheduler, executor, clock::get, 500, timeoutMillis, maxErrors);
    }

    @Test void oneStartForEveryWaiter() {
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.online("vipworld-1"));
        VipStartCoordinator coordinator = coordinator(60_000, 5);
        CompletableFuture<VipStartCoordinator.Outcome> first = coordinator.join("world", UUID.randomUUID());
        CompletableFuture<VipStartCoordinator.Outcome> second = coordinator.join("world", UUID.randomUUID());
        assertEquals(0, host.starts.get(), "the host start runs off the caller thread");
        executor.runAll();
        assertEquals(1, host.starts.get());
        assertEquals(2, coordinator.waiters("world"));
        assertFalse(first.isDone());
        scheduler.runAll();
        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertTrue(first.join().ok());
        assertEquals("vipworld-1", first.join().slot());
        assertEquals(0, coordinator.activeCount());
    }

    @Test void connectsOnlyAfterStatusPingSaysOnline() {
        host.startReplies.add(VipStartCoordinator.Reply.online("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.online("vipworld-1"));
        VipStartCoordinator coordinator = coordinator(60_000, 5);
        CompletableFuture<VipStartCoordinator.Outcome> future = coordinator.join("world", UUID.randomUUID());
        executor.runAll();
        assertFalse(future.isDone(), "the start reply alone must not connect");
        scheduler.runAll();
        assertTrue(future.isDone());
        assertTrue(future.join().ok());
    }

    @Test void failedStartNotifiesEveryWaiterAndResetsState() {
        host.startReplies.add(VipStartCoordinator.Reply.failed(VipStartCoordinator.NO_FREE_SLOT));
        VipStartCoordinator coordinator = coordinator(60_000, 5);
        CompletableFuture<VipStartCoordinator.Outcome> first = coordinator.join("world", UUID.randomUUID());
        CompletableFuture<VipStartCoordinator.Outcome> second = coordinator.join("world", UUID.randomUUID());
        executor.runAll();
        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertFalse(first.join().ok());
        assertEquals(VipStartCoordinator.NO_FREE_SLOT, first.join().reason());
        assertFalse(second.join().ok());
        assertEquals(0, coordinator.activeCount());
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.online("vipworld-1"));
        CompletableFuture<VipStartCoordinator.Outcome> retry = coordinator.join("world", UUID.randomUUID());
        executor.runAll();
        scheduler.runAll();
        assertEquals(2, host.starts.get());
        assertTrue(retry.isDone());
        assertTrue(retry.join().ok());
    }

    @Test void timeoutResetsState() {
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        VipStartCoordinator coordinator = coordinator(1_000, 5);
        CompletableFuture<VipStartCoordinator.Outcome> future = coordinator.join("world", UUID.randomUUID());
        executor.runAll();
        clock.addAndGet(2_000);
        scheduler.runAll();
        assertTrue(future.isDone());
        assertFalse(future.join().ok());
        assertEquals(VipStartCoordinator.TIMEOUT, future.join().reason());
        assertEquals(0, coordinator.activeCount());
    }

    @Test void lastWaiterLeavingCancelsAndClearsState() {
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        VipStartCoordinator coordinator = coordinator(60_000, 5);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CompletableFuture<VipStartCoordinator.Outcome> future = coordinator.join("world", first);
        coordinator.join("world", second);
        executor.runAll();
        coordinator.leave("world", first);
        assertFalse(future.isDone());
        assertEquals(1, coordinator.waiters("world"));
        coordinator.leave("world", second);
        assertTrue(future.isDone());
        assertEquals(VipStartCoordinator.CANCELLED, future.join().reason());
        assertEquals(0, coordinator.activeCount());
        scheduler.runAll();
        assertEquals(0, coordinator.activeCount());
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.online("vipworld-1"));
        CompletableFuture<VipStartCoordinator.Outcome> retry = coordinator.join("world", UUID.randomUUID());
        executor.runAll();
        scheduler.runAll();
        assertEquals(2, host.starts.get());
        assertTrue(retry.join().ok());
    }

    @Test void repeatedHostErrorsFailWithHostUnavailable() {
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.failed("boom"));
        host.statusReplies.add(VipStartCoordinator.Reply.failed("boom"));
        VipStartCoordinator coordinator = coordinator(60_000, 2);
        CompletableFuture<VipStartCoordinator.Outcome> future = coordinator.join("world", UUID.randomUUID());
        executor.runAll();
        scheduler.runAll();
        assertTrue(future.isDone());
        assertEquals(VipStartCoordinator.HOST_UNAVAILABLE, future.join().reason());
        assertEquals(0, coordinator.activeCount());
    }

    @Test void progressReportsSlotAndElapsed() {
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-2"));
        VipStartCoordinator coordinator = coordinator(60_000, 5);
        coordinator.join("world", UUID.randomUUID());
        executor.runAll();
        clock.addAndGet(4_000);
        VipStartCoordinator.Progress progress = coordinator.progress("world").orElseThrow();
        assertEquals("vipworld-2", progress.slot());
        assertEquals(4_000, progress.elapsedMillis());
        assertEquals(60_000, progress.timeoutMillis());
        assertTrue(coordinator.progress("other").isEmpty());
    }

    @Test void concurrentJoinsStartOnlyOnce() throws Exception {
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        host.statusReplies.add(VipStartCoordinator.Reply.online("vipworld-1"));
        VipStartCoordinator coordinator = coordinator(60_000, 5);
        int threads = 8;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<CompletableFuture<VipStartCoordinator.Outcome>>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(pool.submit(() -> { latch.await(); return coordinator.join("world", UUID.randomUUID()); }));
        }
        latch.countDown();
        List<CompletableFuture<VipStartCoordinator.Outcome>> futures = new ArrayList<>();
        for (Future<CompletableFuture<VipStartCoordinator.Outcome>> task : tasks) futures.add(task.get(5, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(threads, coordinator.waiters("world"));
        executor.runAll();
        assertEquals(1, host.starts.get());
        scheduler.runAll();
        for (CompletableFuture<VipStartCoordinator.Outcome> future : futures) assertTrue(future.join().ok());
    }

    @Test void shutdownFailsOpenStarts() {
        host.startReplies.add(VipStartCoordinator.Reply.starting("vipworld-1"));
        VipStartCoordinator coordinator = coordinator(60_000, 5);
        CompletableFuture<VipStartCoordinator.Outcome> future = coordinator.join("world", UUID.randomUUID());
        coordinator.shutdown();
        assertTrue(future.isDone());
        assertEquals(VipStartCoordinator.CANCELLED, future.join().reason());
        assertEquals(0, coordinator.activeCount());
        executor.runAll();
        assertEquals(0, host.starts.get(), "a cancelled start must not reach the host");
    }

    static final class Manual implements VipStartCoordinator.Scheduler {
        final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
        public void later(long delayMillis, Runnable task) { tasks.add(task); }
        void runAll() {
            for (int guard = 0; guard < 100; guard++) {
                Runnable task;
                synchronized (tasks) {
                    if (tasks.isEmpty()) return;
                    task = tasks.remove(0);
                }
                task.run();
            }
        }
    }

    static final class ManualExecutor implements Executor {
        final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
        public void execute(Runnable task) { tasks.add(task); }
        void runAll() {
            for (int guard = 0; guard < 100; guard++) {
                Runnable task;
                synchronized (tasks) {
                    if (tasks.isEmpty()) return;
                    task = tasks.remove(0);
                }
                task.run();
            }
        }
    }

    static final class FakeHost implements VipStartCoordinator.Host {
        final AtomicInteger starts = new AtomicInteger();
        final Deque<VipStartCoordinator.Reply> startReplies = new ArrayDeque<>();
        final Deque<VipStartCoordinator.Reply> statusReplies = new ArrayDeque<>();

        public VipStartCoordinator.Reply start(String worldId) {
            starts.incrementAndGet();
            return startReplies.isEmpty() ? VipStartCoordinator.Reply.failed(VipStartCoordinator.START_FAILED) : startReplies.poll();
        }

        public VipStartCoordinator.Reply status(String slot) {
            return statusReplies.isEmpty() ? VipStartCoordinator.Reply.starting(slot) : statusReplies.poll();
        }
    }
}
