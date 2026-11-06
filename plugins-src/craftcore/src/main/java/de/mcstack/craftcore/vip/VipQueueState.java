package de.mcstack.craftcore.vip;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player VIP queue state, kept apart from Bukkit so "portal while queued" is unit-testable (bug 4).
 * {@code waiting}: world the player waits for; {@code sentSlot}/{@code sentAt}: our last auto-connect;
 * {@code generation}: bumped when the player picks another server, so older waits and retries are dropped.
 */
public final class VipQueueState {
    public record Left(String world, boolean recentConnect, String message) {}

    final Map<UUID, String> waiting = new ConcurrentHashMap<>();
    final Map<UUID, Integer> generation = new ConcurrentHashMap<>();
    final Map<UUID, String> sentSlot = new ConcurrentHashMap<>();
    final Map<UUID, Long> sentAt = new ConcurrentHashMap<>();

    public int generation(UUID id) { return generation.getOrDefault(id, 0); }

    /** Another switch is about to be sent. Null when it does not concern the VIP queue (own connect or nothing queued). */
    public Left beforeSwitch(UUID id, String server, long now) {
        if (server != null && server.equals(sentSlot.get(id))) return null;
        String world = waiting.remove(id);
        boolean hadConnect = sentSlot.remove(id) != null;
        Long at = sentAt.remove(id);
        if (world == null && !hadConnect) return null;
        generation.merge(id, 1, Integer::sum);
        return new Left(world, VipSwitch.recent(at, now), VipSwitch.leftQueueText(server));
    }

    public void clear(UUID id) { waiting.remove(id); sentSlot.remove(id); sentAt.remove(id); }
}
