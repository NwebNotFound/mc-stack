package de.mcstack.craftcore.vip;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Publishes the VIP worlds to the website (POST /api/mc-vip-worlds, contract docs/vip-api.md).
 * The /vip panel renders world cards, the "n of max used" counter and every manage control only
 * from this list.
 * <p>
 * Runs on the lobby inside the queue poller (async, every 5 s, only with VIP_WEB_QUEUE=1). Each
 * round reads the worlds in four queries (at most {@link VipStore#SYNC_MAX_WORLDS} worlds, never
 * truncated: above that limit the round fails loudly) and posts only when the list differs from the
 * last accepted one, so create, rename, start/stop, settings, members and delete show up within
 * one round, whether they came from the web queue, /vw in game or another server. Every
 * {@link #RESYNC_MILLIS} it posts anyway, to heal a lost or reset website copy. The body is a
 * full replace, so sending it twice is harmless.
 */
public final class VipWorldSync {
    public static final String PATH = "/mc-vip-worlds";
    static final long RESYNC_MILLIS = 300_000L;
    static final long LIMIT_CACHE_MILLIS = 300_000L;

    private final VipStore store;
    private final VipWebQueue.Transport transport;
    private final ToIntFunction<UUID> playerLimit;
    private final Map<UUID, long[]> limits = new HashMap<>();
    private String lastBody;
    private long lastSent = Long.MIN_VALUE;

    /** @param playerLimit the owner's player cap by rank (LuckPerms lookup, may block; cached here) */
    public VipWorldSync(VipStore store, VipWebQueue.Transport transport, ToIntFunction<UUID> playerLimit) {
        this.store = store;
        this.transport = transport;
        this.playerLimit = playerLimit;
    }

    /** Posts when the world list changed or the periodic resync is due. Returns whether it posted. */
    public boolean maybeSend(long now) throws Exception {
        String worlds = worlds(now).toString();
        boolean due = lastSent == Long.MIN_VALUE || now - lastSent >= RESYNC_MILLIS;
        if (!due && worlds.equals(lastBody)) return false;
        JsonObject body = new JsonObject();
        body.addProperty("synced_at", now);
        body.add("vip_worlds", JsonParser.parseString(worlds));
        int status = transport.post(PATH, body.toString());
        if (status < 200 || status >= 300) throw new IllegalStateException("world sync HTTP " + status);
        lastBody = worlds;
        lastSent = now;
        return true;
    }

    JsonArray worlds(long now) throws Exception {
        VipStore.SyncSnapshot snap = store.syncSnapshot();
        // The body is a full replace: a cut list would delete the missing worlds on the website.
        // Above the contract limit nothing is sent; the website keeps its last complete list.
        if (snap.worlds().size() > VipStore.SYNC_MAX_WORLDS)
            throw new IllegalStateException(store.liveWorldCount() + " live VIP worlds, more than the limit of "
                + VipStore.SYNC_MAX_WORLDS + "; sync skipped, website keeps its last list");
        Map<String, JsonArray> invites = new HashMap<>();
        for (VipStore.SyncMember m : snap.members()) {
            JsonObject i = new JsonObject();
            i.addProperty("uuid", m.player().toString());
            if (m.name() != null) i.addProperty("name", m.name());
            i.addProperty("role", "trusted".equals(m.role()) ? "trusted" : "member");
            i.addProperty("added_at", m.addedAt());
            invites.computeIfAbsent(m.worldId(), k -> new JsonArray()).add(i);
        }
        JsonArray out = new JsonArray();
        for (VipStore.SyncRow w : snap.worlds()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", w.id());
            o.addProperty("owner_uuid", w.owner().toString());
            if (w.ownerName() != null) o.addProperty("owner_name", w.ownerName());
            o.addProperty("name", w.name());
            o.addProperty("type", w.type());
            o.addProperty("status", status(w.status()));
            o.addProperty("visibility", "private".equals(w.visibility()) ? "private" : "friends");
            if (w.slot() != null) o.addProperty("slot", w.slot());
            o.addProperty("player_limit", limit(w.owner(), now));
            o.addProperty("players", w.slot() == null ? 0 : snap.online().getOrDefault(w.slot(), 0));
            o.add("invites", invites.getOrDefault(w.id(), new JsonArray()));
            JsonArray addons = new JsonArray();
            for (String a : snap.addons().getOrDefault(w.id(), List.of())) addons.add(a);
            o.add("addons", addons);
            o.add("settings", settings(w.settings()).toJson());
            o.addProperty("created_at", w.createdAt());
            if (w.lastStartedAt() != null) o.addProperty("last_started_at", w.lastStartedAt());
            out.add(o);
        }
        return out;
    }

    private int limit(UUID owner, long now) {
        long[] hit = limits.get(owner);
        if (hit != null && now - hit[1] < LIMIT_CACHE_MILLIS) return (int) hit[0];
        int value = Math.max(0, playerLimit.applyAsInt(owner));
        limits.put(owner, new long[] {value, now});
        return value;
    }

    static VipWorldSettings settings(String json) {
        if (json == null || json.isBlank()) return VipWorldSettings.DEFAULTS;
        try { return VipWorldSettings.fromJson(JsonParser.parseString(json)); }
        catch (RuntimeException e) { return VipWorldSettings.DEFAULTS; }
    }

    /** The website knows stopped/starting/running/stopping/error; a queued start shows as starting. */
    static String status(String db) {
        String s = db == null ? "" : db.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "running", "starting", "stopping", "error", "stopped" -> s;
            case "queued" -> "starting";
            default -> "stopped";
        };
    }
}
