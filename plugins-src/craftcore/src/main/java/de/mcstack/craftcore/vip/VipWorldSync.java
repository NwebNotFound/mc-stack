package de.mcstack.craftcore.vip;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * Publishes the VIP worlds to the website (POST /api/mc-vip-worlds, contract docs/vip-api.md).
 * The /vip panel renders world cards, the "n of max used" counter and every manage control only
 * from this list; without it the panel shows no worlds at all, even after a create from the web.
 * Runs on the lobby next to the queue poller: right after a round that answered a command, and
 * otherwise once a minute so changes made in game (/vw) reach the panel too.
 */
public final class VipWorldSync {
    public static final String PATH = "/mc-vip-worlds";
    static final long EVERY_MILLIS = 60_000L;

    private final VipStore store;
    private final VipWebQueue.Transport transport;
    private long lastSent = Long.MIN_VALUE;

    public VipWorldSync(VipStore store, VipWebQueue.Transport transport) {
        this.store = store;
        this.transport = transport;
    }

    /** Sends when commands were answered in this round or the last sync is a minute old. */
    public boolean maybeSend(long now, int answered) throws Exception {
        if (answered <= 0 && lastSent != Long.MIN_VALUE && now - lastSent < EVERY_MILLIS) return false;
        int status = transport.post(PATH, payload().toString());
        if (status < 200 || status >= 300) throw new IllegalStateException("world sync HTTP " + status);
        lastSent = now;
        return true;
    }

    JsonObject payload() throws Exception {
        JsonArray worlds = new JsonArray();
        for (VipStore.SyncRow w : store.syncRows()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", w.id());
            o.addProperty("owner_uuid", w.owner().toString());
            if (w.ownerName() != null) o.addProperty("owner_name", w.ownerName());
            o.addProperty("name", w.name());
            o.addProperty("type", w.type());
            o.addProperty("status", status(w.status()));
            if (w.slot() != null) o.addProperty("slot", w.slot());
            o.addProperty("visibility", "private".equals(w.visibility()) ? "private" : "friends");
            o.addProperty("created_at", w.createdAt());
            if (w.lastStartedAt() != null) o.addProperty("last_started_at", w.lastStartedAt());
            JsonArray invites = new JsonArray();
            for (VipStore.Member m : store.members(w.id())) {
                JsonObject i = new JsonObject();
                i.addProperty("uuid", m.player().toString());
                if (m.name() != null) i.addProperty("name", m.name());
                i.addProperty("role", "trusted".equals(m.role()) ? "trusted" : "member");
                invites.add(i);
            }
            o.add("invites", invites);
            JsonArray addons = new JsonArray();
            for (String a : store.addons(w.id())) addons.add(a);
            o.add("addons", addons);
            o.add("settings", store.settings(w.id()).toJson());
            worlds.add(o);
        }
        JsonObject body = new JsonObject();
        body.add("vip_worlds", worlds);
        return body;
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
