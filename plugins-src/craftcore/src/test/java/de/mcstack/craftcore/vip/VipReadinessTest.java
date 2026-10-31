package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VipReadinessTest {
    private static final String WORLD = "world", SLOT = "vipworld-1";
    @Test void waitsUntilMatchingWorldIsOnline() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        boolean ready = VipReadiness.await(WORLD, SLOT, 30, () -> {
            int call = calls.incrementAndGet();
            return List.of(new VipReconcile.Slot(SLOT, call < 3 ? "starting" : "online", WORLD));
        }, clock::get, millis -> { assertEquals(3000, millis); clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)); });
        assertTrue(ready); assertEquals(3, calls.get());
    }
    @Test void timeoutDoesNotAcceptOtherWorldOrSlot() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        boolean ready = VipReadiness.await(WORLD, SLOT, 1, () -> {
            calls.incrementAndGet(); return List.of(new VipReconcile.Slot(SLOT, "online", "other"), new VipReconcile.Slot("vipworld-2", "online", WORLD));
        }, clock::get, millis -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)));
        assertFalse(ready); assertEquals(30, TimeUnit.NANOSECONDS.toSeconds(clock.get())); assertEquals(9, calls.get());
        assertEquals(30, VipReadiness.timeoutSeconds(0)); assertEquals(180, VipReadiness.timeoutSeconds(180));
    }
    @Test void unavailableHostContinuesPolling() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        assertTrue(VipReadiness.await(WORLD, SLOT, 30, () -> calls.incrementAndGet() == 1 ? null : List.of(new VipReconcile.Slot(SLOT,"online",WORLD)), clock::get, millis -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis))));
        assertEquals(2, calls.get());
    }
}
