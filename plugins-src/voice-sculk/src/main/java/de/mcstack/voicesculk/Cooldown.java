package de.mcstack.voicesculk;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class Cooldown {
    private final ConcurrentHashMap<UUID, Long> last = new ConcurrentHashMap<>();
    private final long interval;
    Cooldown(long interval) { this.interval = Math.max(1, interval); }
    boolean accept(UUID id, long now) {
        final boolean[] accepted = {false};
        last.compute(id, (key, previous) -> {
            if (previous == null || now - previous >= interval || now < previous) {
                accepted[0] = true;
                return now;
            }
            return previous;
        });
        return accepted[0];
    }
    void remove(UUID id) { last.remove(id); }
}
