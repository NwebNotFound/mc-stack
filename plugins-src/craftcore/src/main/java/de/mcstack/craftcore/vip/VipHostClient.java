package de.mcstack.craftcore.vip;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class VipHostClient {
    public enum Outcome { STARTING, ONLINE, STOPPED, NO_FREE_SLOT, RAM_BUDGET, WORLD_NOT_FOUND, ERROR }
    public record Result(Outcome outcome, String slot) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base = System.getenv().getOrDefault("VIP_HOST_URL", "http://host.docker.internal:25599").replaceAll("/+$", "");
    private final String token = System.getenv().getOrDefault("VIP_HOST_TOKEN", "");
    public static Result parseStart(int code, String body) {
        JsonObject json = object(body); String error = field(json, "error");
        if (code == 409 && "no_free_slot".equals(error)) return new Result(Outcome.NO_FREE_SLOT, null);
        if (code == 503 && "ram_budget".equals(error)) return new Result(Outcome.RAM_BUDGET, null);
        if (code == 404 && "error".equals(field(json, "status")) && "world_not_found".equals(field(json, "code"))) return new Result(Outcome.WORLD_NOT_FOUND, null);
        String slot = field(json, "slot");
        if (code == 200 && "running".equals(field(json, "status")) && validSlot(slot)) return new Result(Outcome.ONLINE, slot);
        if (code == 202 && "starting".equals(field(json, "status")) && validSlot(slot)) return new Result(Outcome.STARTING, slot);
        return new Result(Outcome.ERROR, null);
    }
    public static Result parseStatus(int code, String body, String slot) {
        if (code != 200 || !validSlot(slot)) return new Result(Outcome.ERROR, null);
        JsonObject json = object(body);
        try {
            if (json.has("slots") && json.get("slots").isJsonArray()) {
                for (var value : json.getAsJsonArray("slots")) {
                    if (!value.isJsonObject()) continue;
                    JsonObject entry = value.getAsJsonObject();
                    if (!slot.equals(field(entry, "slot"))) continue;
                    String status = field(entry, "status");
                    if ("online".equals(status)) return new Result(Outcome.ONLINE, slot);
                    if ("starting".equals(status) || "sleeping".equals(status) || "offline".equals(status)) return new Result(Outcome.STARTING, slot);
                    return new Result(Outcome.ERROR, null);
                }
                return new Result(Outcome.ERROR, null);
            }
            if (json.has("slots") && json.get("slots").isJsonObject()) {
                var value = json.getAsJsonObject("slots").get(slot);
                if (value == null) return new Result(Outcome.ERROR, null);
                String status = value.isJsonObject() ? field(value.getAsJsonObject(), "status") : value.getAsString();
                return new Result("online".equals(status) ? Outcome.ONLINE : Outcome.STARTING, slot);
            }
            if (slot.equals(field(json, "slot"))) return new Result("online".equals(field(json, "status")) ? Outcome.ONLINE : Outcome.STARTING, slot);
        } catch (RuntimeException ignored) {}
        return new Result(Outcome.ERROR, null);
    }
    public static Result parseStop(int code, String body) { return new Result(code >= 200 && code < 300 ? Outcome.STOPPED : Outcome.ERROR, null); }
    private static boolean validSlot(String slot) { return slot != null && slot.matches("vipworld-[1-9][0-9]*"); }
    private static JsonObject object(String body) { try { return JsonParser.parseString(body).getAsJsonObject(); } catch (Exception e) { return new JsonObject(); } }
    private static String field(JsonObject json, String key) {
        try { var v=json.get(key); if (v != null && v.isJsonObject()) v=v.getAsJsonObject().get("code"); return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null; } catch (Exception e) { return null; }
    }
    private HttpRequest.Builder request(String path, int timeout) { return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeout)).header("X-VIP-Token", token); }
    private Result post(String path, JsonObject json, boolean start) {
        try {
            var response=client.send(request(path, 90).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString())).build(), HttpResponse.BodyHandlers.ofString());
            return start ? parseStart(response.statusCode(), response.body()) : parseStop(response.statusCode(), response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new Result(Outcome.ERROR, null); }
        catch (Exception e) { return new Result(Outcome.ERROR, null); }
    }
    public Result start(String id) { JsonObject json=new JsonObject(); json.addProperty("worldId", id); return post("/vip/start", json, true); }
    public Result stop(String slot) { JsonObject json=new JsonObject(); json.addProperty("slot", slot); return post("/vip/stop", json, false); }
    public Result status(String slot) {
        try { var response=client.send(request("/vip/status", 5).GET().build(), HttpResponse.BodyHandlers.ofString()); return parseStatus(response.statusCode(), response.body(), slot); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return new Result(Outcome.ERROR, null); }
        catch (Exception e) { return new Result(Outcome.ERROR, null); }
    }
}
