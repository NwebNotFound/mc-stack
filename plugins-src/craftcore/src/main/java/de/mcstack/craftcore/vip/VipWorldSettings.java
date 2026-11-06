package de.mcstack.craftcore.vip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The six plain world rules the web panel sets. Kept pure so the queue and the sync can share it
 * without a server. Unknown keys are an error for browser input ({@link #merge}) and are dropped
 * quietly for the plugin sync ({@link #tolerant}), the same split the website uses.
 */
public record VipWorldSettings(String gamemode, String difficulty, boolean pvp, boolean keepInventory,
                               boolean mobGriefing, boolean daylightCycle) {
    public static final Set<String> GAMEMODES = Set.of("survival", "creative", "adventure");
    public static final Set<String> DIFFICULTIES = Set.of("peaceful", "easy", "normal", "hard");
    public static final VipWorldSettings DEFAULTS =
        new VipWorldSettings("survival", "normal", true, false, true, true);

    /** A key that is not in the contract or a value outside its list. */
    public static final class InvalidSettingException extends Exception {
        private final String key;
        public InvalidSettingException(String key) { super(key); this.key = key; }
        public String key() { return key; }
    }

    public VipWorldSettings with(String key, boolean value) {
        return new VipWorldSettings(gamemode, difficulty,
            "pvp".equals(key) ? value : pvp,
            "keepInventory".equals(key) ? value : keepInventory,
            "mobGriefing".equals(key) ? value : mobGriefing,
            "doDaylightCycle".equals(key) ? value : daylightCycle);
    }

    public VipWorldSettings with(String key, String value) {
        return new VipWorldSettings("gamemode".equals(key) ? value : gamemode,
            "difficulty".equals(key) ? value : difficulty, pvp, keepInventory, mobGriefing, daylightCycle);
    }

    /** Browser input: every key must be known and every value inside its list. */
    public VipWorldSettings merge(JsonObject partial) throws InvalidSettingException {
        if (partial == null) return this;
        VipWorldSettings out = this;
        for (Map.Entry<String, JsonElement> e : partial.entrySet()) {
            String key = e.getKey();
            JsonElement value = e.getValue();
            switch (key) {
                case "gamemode" -> out = out.with(key, text(key, value, GAMEMODES));
                case "difficulty" -> out = out.with(key, text(key, value, DIFFICULTIES));
                case "pvp", "keepInventory", "mobGriefing", "doDaylightCycle" -> {
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                        throw new InvalidSettingException(key);
                    out = out.with(key, value.getAsBoolean());
                }
                default -> throw new InvalidSettingException(key);
            }
        }
        return out;
    }

    /** Plugin sync: known keys with valid values are kept, everything else falls away. */
    public static VipWorldSettings tolerant(JsonObject raw) {
        if (raw == null) return DEFAULTS;
        VipWorldSettings out = DEFAULTS;
        for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
            try { out = out.merge(one(e.getKey(), e.getValue())); }
            catch (InvalidSettingException ignored) { /* drop the key, keep the world */ }
        }
        return out;
    }

    public static VipWorldSettings fromJson(JsonElement json) {
        return json != null && json.isJsonObject() ? tolerant(json.getAsJsonObject()) : DEFAULTS;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("gamemode", gamemode);
        json.addProperty("difficulty", difficulty);
        json.addProperty("pvp", pvp);
        json.addProperty("keepInventory", keepInventory);
        json.addProperty("mobGriefing", mobGriefing);
        json.addProperty("doDaylightCycle", daylightCycle);
        return json;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("gamemode", gamemode);
        map.put("difficulty", difficulty);
        map.put("pvp", pvp);
        map.put("keepInventory", keepInventory);
        map.put("mobGriefing", mobGriefing);
        map.put("doDaylightCycle", daylightCycle);
        return map;
    }

    private static JsonObject one(String key, JsonElement value) {
        JsonObject json = new JsonObject();
        json.add(key, value);
        return json;
    }

    private static String text(String key, JsonElement value, Set<String> allowed) throws InvalidSettingException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new InvalidSettingException(key);
        String text = value.getAsString();
        if (!allowed.contains(text)) throw new InvalidSettingException(key);
        return text;
    }
}
