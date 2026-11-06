package de.mcstack.craftcore.vip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/**
 * One item from {@code GET /api/mc-vip-commands/pending}. Parsing is strict about the identifiers
 * the contract fixes and lenient about unknown fields, which the contract tells us to ignore.
 */
public record VipCommand(String requestId, String discordId, long createdAt, String action, String worldId,
                         String name, String type, String visibility, String role, String confirm,
                         JsonObject settings, boolean hasSettings, String actorUuid, Boolean linked) {
    /** Items without a website link UUID (tests, older senders). */
    public VipCommand(String requestId, String discordId, long createdAt, String action, String worldId,
                      String name, String type, String visibility, String role, String confirm,
                      JsonObject settings, boolean hasSettings) {
        this(requestId, discordId, createdAt, action, worldId, name, type, visibility, role, confirm, settings, hasSettings, null, null);
    }
    public static final Set<String> ACTIONS = Set.of(
        "create", "rename", "start", "stop", "settings", "set_visibility",
        "invite", "uninvite", "trust", "delete");
    public static final Set<String> TYPES = Set.of("normal", "flat", "void");
    public static final Set<String> VISIBILITIES = Set.of("friends", "private");
    public static final Set<String> ROLES = Set.of("member", "trusted");
    private static final String RID = "^[A-Za-z0-9_-]{1,64}$";
    private static final String DISCORD = "^[0-9]{1,20}$";
    private static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    /** {@code error} is the ack reason when the item itself is broken, otherwise empty. */
    /**
     * {@code error} with no command: no usable identifiers, nothing to acknowledge.
     * {@code error} with a command: identifiers are fine, the item is acked denied with that reason
     * and not executed (e.g. a malformed actor_uuid, review #168).
     */
    public record Parsed(VipCommand command, String error) {
        public static Parsed error(String reason) { return new Parsed(null, reason); }
        public boolean ok() { return command != null && error.isEmpty(); }
        public boolean deny() { return command != null && !error.isEmpty(); }
    }

    public static Parsed parse(JsonElement json) {
        if (json == null || !json.isJsonObject()) return Parsed.error("invalid_request");
        JsonObject o = json.getAsJsonObject();
        String requestId = text(o, "request_id");
        if (requestId == null || !requestId.matches(RID)) return Parsed.error("invalid_request_id");
        String discordId = text(o, "discord_id");
        if (discordId == null || !discordId.matches(DISCORD)) return Parsed.error("invalid_discord_id");
        Long created = number(o, "created_at");
        if (created == null) return Parsed.error("invalid_timestamp");
        // The contract: anything below 1e12 is not a millisecond value and must not run.
        if (created < 1_000_000_000_000L) return Parsed.error("invalid_timestamp");
        String action = text(o, "action");
        if (action == null || action.isBlank()) return Parsed.error("invalid_action");
        String worldId = text(o, "world_id");
        if (worldId != null && !worldId.matches(UUID)) return Parsed.error("invalid_world_id");
        JsonObject settings = object(o, "settings");
        // The website resolves the caller's verified mc_links row (D1) and sends that UUID as
        // actor_uuid (older contract text: mc_uuid). A malformed value is a broken item.
        // The website rechecks the CURRENT link when it serves the item (linked=true only if the
        // mc_links row is still verified, has the Discord role and the same UUID). Without
        // linked=true the website UUID is not trusted at all.
        String actorUuid = text(o, "actor_uuid");
        if (actorUuid == null) actorUuid = text(o, "mc_uuid");
        String badActor = "";
        if (actorUuid != null) {
            actorUuid = actorUuid.toLowerCase(java.util.Locale.ROOT);
            if (!actorUuid.matches(UUID)) { badActor = "invalid_player"; actorUuid = null; }
        }
        JsonElement linkedEl = o.get("linked");
        Boolean linked = linkedEl != null && linkedEl.isJsonPrimitive() && linkedEl.getAsJsonPrimitive().isBoolean() ? linkedEl.getAsBoolean() : null;
        return new Parsed(new VipCommand(requestId, discordId, created, action.toLowerCase(java.util.Locale.ROOT),
            worldId, text(o, "name"), text(o, "type"), text(o, "visibility"), text(o, "role"),
            text(o, "confirm"), settings, settings != null, actorUuid, linked), badActor);
    }

    /** Unknown actions are skipped with a log line, never executed. */
    public boolean known() { return ACTIONS.contains(action); }

    public boolean hasWorld() { return worldId != null && !worldId.isBlank(); }

    private static String text(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
    }

    private static Long number(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsLong() : null;
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }
}
