package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Poll, idempotency, expiry and unknown actions — the parts that must not double-run anything. */
class VipWebQueueTest {
    private static final long NOW = 1_793_500_600_000L;

    private static final class FakeTransport implements VipWebQueue.Transport {
        String body = "{\"items\":[]}";
        final List<String> posts = new ArrayList<>();
        int postCode = 200;
        @Override public String get(String path) { return body; }
        @Override public int post(String path, String body) { posts.add(body); return postCode; }
    }

    private static final class MemoryAcks implements VipWebQueue.Acks {
        final Map<String, String> saved = new HashMap<>();
        @Override public java.util.Optional<VipWebQueue.Outcome> look(String discordId, String requestId) {
            String stored = saved.get(discordId + "/" + requestId);
            if (stored == null) return java.util.Optional.empty();
            int split = stored.indexOf(':');
            return java.util.Optional.of(new VipWebQueue.Outcome(stored.substring(0, split), stored.substring(split + 1)));
        }
        @Override public void save(String discordId, String requestId, String status, String reason) {
            saved.put(discordId + "/" + requestId, status + ":" + reason);
        }
    }

    private static String item(String requestId, String action, long createdAt) {
        return "{\"request_id\":\"" + requestId + "\",\"discord_id\":\"100000000000000001\","
            + "\"created_at\":" + createdAt + ",\"action\":\"" + action + "\","
            + "\"world_id\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\"}";
    }

    @Test void appliesOnceAndResendsTheStoredAck() throws Exception {
        FakeTransport http = new FakeTransport();
        MemoryAcks acks = new MemoryAcks();
        List<String> applied = new ArrayList<>();
        http.body = "{\"items\":[" + item("req-1", "stop", NOW - 1000) + "]}";
        VipWebQueue queue = new VipWebQueue(http, acks,
            c -> { applied.add(c.requestId()); return VipWebQueue.Outcome.done(); }, line -> { });

        assertEquals(1, queue.pollOnce(NOW));
        assertEquals(List.of("req-1"), applied);
        assertEquals("done:", acks.saved.get("100000000000000001/req-1"));
        assertTrue(http.posts.get(0).contains("\"status\":\"done\""));

        // Dieselbe Kennung bleibt im Panel offen, weil der Ack-POST verloren ging: die gespeicherte
        // Entscheidung geht erneut raus, ausgefuehrt wird nichts.
        assertEquals(1, queue.pollOnce(NOW + 5000));
        assertEquals(1, applied.size());
        assertEquals(2, http.posts.size());
        assertTrue(http.posts.get(1).contains("\"request_id\":\"req-1\""));
    }

    @Test void lostAckIsResentOnTheNextPoll() throws Exception {
        FakeTransport http = new FakeTransport();
        MemoryAcks acks = new MemoryAcks();
        List<String> applied = new ArrayList<>();
        http.body = "{\"items\":[" + item("req-9", "rename", NOW - 1000) + "]}";
        http.postCode = 500;
        VipWebQueue queue = new VipWebQueue(http, acks,
            c -> { applied.add(c.requestId()); return VipWebQueue.Outcome.denied("not_owner"); }, line -> { });

        assertThrows(IllegalStateException.class, () -> queue.pollOnce(NOW));
        assertEquals(1, applied.size());
        assertEquals("denied:not_owner", acks.saved.get("100000000000000001/req-9"));

        http.postCode = 200;
        assertEquals(1, queue.pollOnce(NOW + 5000));
        assertEquals(1, applied.size(), "kein zweiter Lauf");
        assertTrue(http.posts.get(1).contains("\"reason\":\"not_owner\""));
    }

    @Test void expiredItemsAreAckedWithoutRunning() throws Exception {
        FakeTransport http = new FakeTransport();
        MemoryAcks acks = new MemoryAcks();
        List<String> applied = new ArrayList<>();
        http.body = "{\"items\":[" + item("req-old", "stop", NOW - VipWebQueue.TTL_MILLIS) + "]}";
        VipWebQueue queue = new VipWebQueue(http, acks, c -> { applied.add(c.requestId()); return VipWebQueue.Outcome.done(); }, line -> { });

        queue.pollOnce(NOW);
        assertTrue(applied.isEmpty());
        assertEquals("expired:", acks.saved.get("100000000000000001/req-old"));
        assertTrue(http.posts.get(0).contains("\"status\":\"expired\""));
    }

    @Test void unknownActionIsLoggedAndNotRun() throws Exception {
        FakeTransport http = new FakeTransport();
        MemoryAcks acks = new MemoryAcks();
        List<String> lines = new ArrayList<>();
        http.body = "{\"items\":[" + item("req-x", "explode", NOW - 1000) + "]}";
        VipWebQueue queue = new VipWebQueue(http, acks, c -> VipWebQueue.Outcome.done(), lines::add);

        queue.pollOnce(NOW);
        assertEquals("denied:unknown_action", acks.saved.get("100000000000000001/req-x"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("unbekannte Aktion") && l.contains("explode")));
    }

    @Test void invalidIdentifierIsSkippedAndNeverAcked() throws Exception {
        FakeTransport http = new FakeTransport();
        MemoryAcks acks = new MemoryAcks();
        http.body = "{\"items\":[{\"request_id\":\"bad id!\",\"discord_id\":\"1\",\"created_at\":" + NOW
            + ",\"action\":\"stop\"}]}";
        VipWebQueue queue = new VipWebQueue(http, acks, c -> VipWebQueue.Outcome.done(), line -> { });

        assertEquals(0, queue.pollOnce(NOW));
        assertTrue(acks.saved.isEmpty());
        assertTrue(http.posts.isEmpty());
    }

    @Test void denialsKeepTheirReason() throws Exception {
        FakeTransport http = new FakeTransport();
        MemoryAcks acks = new MemoryAcks();
        http.body = "{\"items\":[" + item("req-2", "delete", NOW - 1000) + "]}";
        VipWebQueue queue = new VipWebQueue(http, acks,
            c -> VipWebQueue.Outcome.denied("not_owner"), line -> { });

        queue.pollOnce(NOW);
        assertEquals("denied:not_owner", acks.saved.get("100000000000000001/req-2"));
        assertTrue(http.posts.get(0).contains("\"reason\":\"not_owner\""));
    }

    @Test void brokenPollBodyDoesNothing() throws Exception {
        FakeTransport http = new FakeTransport();
        MemoryAcks acks = new MemoryAcks();
        http.body = "<html>nope</html>";
        VipWebQueue queue = new VipWebQueue(http, acks, c -> VipWebQueue.Outcome.done(), line -> { });
        assertEquals(0, queue.pollOnce(NOW));
        assertTrue(http.posts.isEmpty());
    }
}
