package de.mcstack.craftcore.pvp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PvpQueue {
    private final Map<UUID, String> players = new LinkedHashMap<>();

    public record Pair(UUID a, String kitA, UUID b, String kitB) {}

    public synchronized void join(UUID player, String kit) { players.put(player, kit); }
    public synchronized boolean leave(UUID player) {
        if (!players.containsKey(player)) return false;
        players.remove(player);
        return true;
    }
    public synchronized int position(UUID player) { int n = 0; for (UUID id : players.keySet()) { n++; if (id.equals(player)) return n; } return 0; }
    public synchronized int size() { return players.size(); }
    public synchronized boolean contains(UUID player) { return players.containsKey(player); }
    public synchronized Optional<Pair> poll() {
        if (players.size() < 2) return Optional.empty();
        Iterator<Map.Entry<UUID, String>> iterator = players.entrySet().iterator();
        Map.Entry<UUID, String> first = iterator.next();
        UUID a = first.getKey();
        String kitA = first.getValue();
        iterator.remove();
        Map.Entry<UUID, String> second = iterator.next();
        Pair pair = new Pair(a, kitA, second.getKey(), second.getValue());
        iterator.remove();
        return Optional.of(pair);
    }
}
