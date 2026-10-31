package de.mcstack.craftcore.pvp;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class PvpHostClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base = System.getenv().getOrDefault("PVP_HOST_URL", "http://host.docker.internal:25599").replaceAll("/+$", "");
    private final String token = System.getenv().getOrDefault("PVP_HOST_TOKEN", "");

    private HttpRequest.Builder request(String path, int seconds) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(seconds)).header("X-PvP-Token", token);
    }
    public String status() {
        try {
            var response = client.send(request("/status", 5).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parseStatus(response.body()) : "offline";
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return "offline"; }
        catch (Exception e) { return "offline"; }
    }
    public boolean start() {
        try {
            return client.send(request("/start", 90).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }
    static String parseStatus(String json) {
        try {
            var value = JsonParser.parseString(json).getAsJsonObject().get("status");
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "offline";
        } catch (Exception e) { return "offline"; }
    }
}
