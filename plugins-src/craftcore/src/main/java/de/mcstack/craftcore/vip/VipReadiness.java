package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Polls host state without depending on Bukkit, so readiness rules are testable. */
public final class VipReadiness {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public static int timeoutSeconds(int configured) { return Math.max(30, configured); }
    public static boolean await(String worldId, String slot, int timeoutSeconds,
                                Supplier<List<VipReconcile.Slot>> query, LongSupplier clock, Sleeper sleeper)
            throws InterruptedException {
        long deadline = clock.getAsLong() + TimeUnit.SECONDS.toNanos(timeoutSeconds(timeoutSeconds));
        while (true) {
            long remaining = deadline - clock.getAsLong();
            if (remaining <= 0) return false;
            sleeper.sleep(Math.min(3000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
            if (clock.getAsLong() >= deadline) return false;
            List<VipReconcile.Slot> slots = query.get();
            if (slots != null && slots.stream().anyMatch(s -> slot.equals(s.slot()) && worldId.equals(s.worldId()) && "online".equals(s.status()))) return true;
        }
    }
}
