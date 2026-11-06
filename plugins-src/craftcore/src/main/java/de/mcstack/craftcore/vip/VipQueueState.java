package de.mcstack.craftcore.vip;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-player VIP queue state, kept apart from Bukkit so "portal while queued" is unit-testable (bug 4).
 * {@code waiting}: world the player waits for; {@code sentSlot}/{@code sentAt}: our last auto-connect;
 * {@code generation}: replaced when the player picks another server, so older waits and retries are dropped. Every value
 * is new (one counter for all players), so dropping the entry on quit cannot let an old retry match after a rejoin.
 */
public final class VipQueueState {
    public record Left(String world, boolean recentConnect, String message) {}

    final Map<UUID, String> waiting = new ConcurrentHashMap<>();
    final Map<UUID, Integer> generation = new ConcurrentHashMap<>();
    final Map<UUID, String> sentSlot = new ConcurrentHashMap<>();
    final Map<UUID, Long> sentAt = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    /** The player's current generation, for a wait or retry that starts now. */
    public int generation(UUID id) { return generation.computeIfAbsent(id, key -> counter.incrementAndGet()); }

    /** Whether a wait or retry of that generation is still wanted; never creates an entry (it may run after a quit). */
    public boolean current(UUID id, int gen) { Integer value = generation.get(id); return value != null && value == gen; }

    /** Another switch is about to be sent. Null when it does not concern the VIP queue (own connect or nothing queued). */
    public Left beforeSwitch(UUID id, String server, long now) {
        if (server != null && server.equals(sentSlot.get(id))) return null;
        String world = waiting.remove(id);
        boolean hadConnect = sentSlot.remove(id) != null;
        Long at = sentAt.remove(id);
        if (world == null && !hadConnect) return null;
        generation.put(id, counter.incrementAndGet());
        return new Left(world, VipSwitch.recent(at, now), VipSwitch.leftQueueText(server));
    }

    public void clear(UUID id) { waiting.remove(id); sentSlot.remove(id); sentAt.remove(id); }

    /** Quit: drops everything of the player, the generation included, so the maps do not grow per player. */
    public void forget(UUID id) { clear(id); generation.remove(id); }
}
