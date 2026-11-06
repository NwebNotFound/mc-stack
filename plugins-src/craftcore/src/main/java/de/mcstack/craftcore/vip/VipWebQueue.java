package de.mcstack.craftcore.vip;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Polls {@code GET /api/mc-vip-commands/pending}, applies each item once and acks it. The ack key is
 * {@code (discord_id, request_id)}: a second poll of the same pair returns the stored decision and
 * never runs the action again. Expired items are acked {@code expired} without running.
 */
public final class VipWebQueue {
    public static final long TTL_MILLIS = 600_000L;
    public static final String OK = "done", DENIED = "denied", FAILED = "failed", EXPIRED = "expired";

    public record Outcome(String status, String reason) {
        public static Outcome done() { return new Outcome(OK, ""); }
        public static Outcome denied(String reason) { return new Outcome(DENIED, reason == null ? "" : reason); }
        public static Outcome failed(String reason) { return new Outcome(FAILED, reason == null ? "" : reason); }
    }

    /** One poll round: GET returns the body, POST returns the status code. */
    public interface Transport {
        String get(String path) throws Exception;
        int post(String path, String body) throws Exception;
    }
    public interface Acks {
        /** Die gespeicherte Entscheidung, damit ein verlorener Ack-POST wiederholt werden kann. */
        Optional<Outcome> look(String discordId, String requestId) throws Exception;
        void save(String discordId, String requestId, String status, String reason) throws Exception;
    }
    @FunctionalInterface public interface Applier { Outcome apply(VipCommand command); }

    private final Transport transport;
    private final Acks acks;
    private final Applier applier;
    private final Consumer<String> log;

    public VipWebQueue(Transport transport, Acks acks, Applier applier, Consumer<String> log) {
        this.transport = transport;
        this.acks = acks;
        this.applier = applier;
        this.log = log == null ? ignored -> { } : log;
    }

    /** Runs one round. Returns how many items were answered in this round. */
    public int pollOnce(long now) throws Exception {
        String body = transport.get("/mc-vip-commands/pending");
        List<JsonObject> acksOut = new ArrayList<>();
        JsonArray items = items(body);
        if (items == null) return 0;
        int answered = 0;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            VipCommand.Parsed item = VipCommand.parse(element);
            if (item.deny()) {
                // Valid request and Discord IDs, broken content (e.g. malformed actor_uuid): ack
                // denied once, never execute. An earlier stored ack is resent unchanged.
                VipCommand command = item.command();
                try {
                    Optional<Outcome> done = acks.look(command.discordId(), command.requestId());
                    Outcome outcome = done.orElse(Outcome.denied(item.error()));
                    if (done.isEmpty()) acks.save(command.discordId(), command.requestId(), outcome.status(), outcome.reason());
                    acksOut.add(ack(command, outcome));
                    answered++;
                } catch (Exception e) {
                    log.accept("VIP-Queue: " + command.requestId() + " fehlgeschlagen (" + e.getClass().getSimpleName() + ")");
                }
                continue;
            }
            if (!item.ok()) {
                // Ohne brauchbare Kennung gibt es nichts zu quittieren; die Zeile bleibt im Log.
                log.accept("VIP-Queue: Auftrag ohne gültige Kennung übersprungen (" + item.error() + ")");
                continue;
            }
            VipCommand command = item.command();
            try {
                Optional<Outcome> done = acks.look(command.discordId(), command.requestId());
                if (done.isPresent()) {
                    // Der Auftrag lief schon, aber der Ack-POST kann verloren gegangen sein: die
                    // gespeicherte Entscheidung erneut mitschicken, ohne ihn noch einmal auszufuehren.
                    acksOut.add(ack(command, done.get()));
                    answered++;
                    continue;
                }
                Outcome outcome;
                if (now - command.createdAt() >= TTL_MILLIS) {
                    outcome = new Outcome(EXPIRED, "");
                } else if (!command.known()) {
                    // Unbekannte Aktionen werden protokolliert und quittiert, damit sie nicht ewig
                    // als offen im Panel stehen; ausgeführt wird nichts.
                    log.accept("VIP-Queue: unbekannte Aktion '" + command.action() + "' für " + command.requestId());
                    outcome = Outcome.denied("unknown_action");
                } else {
                    outcome = applier.apply(command);
                    if (outcome == null) outcome = Outcome.failed("host_unavailable");
                }
                acks.save(command.discordId(), command.requestId(), outcome.status(), outcome.reason());
                acksOut.add(ack(command, outcome));
                answered++;
            } catch (Exception e) {
                log.accept("VIP-Queue: " + command.requestId() + " fehlgeschlagen (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (!acksOut.isEmpty()) post(acksOut);
        return answered;
    }

    private void post(List<JsonObject> acksOut) throws Exception {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        acksOut.forEach(array::add);
        root.add("acks", array);
        int code = transport.post("/mc-vip-commands/ack", root.toString());
        if (code < 200 || code >= 300) throw new IllegalStateException("ack HTTP " + code);
    }

    private static JsonObject ack(VipCommand c, Outcome outcome) {
        JsonObject json = new JsonObject();
        json.addProperty("request_id", c.requestId());
        json.addProperty("discord_id", c.discordId());
        json.addProperty("status", outcome.status());
        json.addProperty("reason", outcome.reason());
        return json;
    }

    private static JsonArray items(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement items = root.get("items");
            return items != null && items.isJsonArray() ? items.getAsJsonArray() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
