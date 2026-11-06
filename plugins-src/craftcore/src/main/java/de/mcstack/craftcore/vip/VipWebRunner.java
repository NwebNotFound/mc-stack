package de.mcstack.craftcore.vip;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Polls the website queue on the lobby. Off unless {@code VIP_WEB_QUEUE=1}, so the code can ship
 * before VIP is enabled. Every poll runs off the server thread; a failing round only logs.
 */
public final class VipWebRunner {
    public static final String ENABLE = "VIP_WEB_QUEUE";
    private static final long POLL_MILLIS = 5_000L;

    private final JavaPlugin plugin;
    private VipWebQueue queue;
    private VipWorldSync sync;
    private volatile boolean stopped;

    public VipWebRunner(JavaPlugin plugin) { this.plugin = plugin; }

    public static boolean enabled() { return "1".equals(System.getenv().getOrDefault(ENABLE, "").trim()); }

    public void start(VipStore.ConnectionSource db, VipHostClient host, VipConfig.Values config) {
        if (!enabled()) return;
        String base = System.getenv().getOrDefault("MC_WEB_API", "https://nwebnotfound.dev/api").replaceAll("/+$", "");
        String token = System.getenv().getOrDefault("MC_INGEST_TOKEN", "");
        if (token.isBlank()) { plugin.getLogger().warning("VIP-Queue: " + ENABLE + " gesetzt, aber MC_INGEST_TOKEN fehlt."); return; }
        VipStore store = new VipStore(db);
        VipWebCommands commands = new VipWebCommands(store,
            new VipWebCommands.Host() {
                public VipHostClient.Result start(String id) { return host.start(id); }
                public VipHostClient.Result stop(String slot) { return host.stop(slot); }
            },
            uuid -> rank(uuid), db, config.adminSlots(), config.trustedMayStart());
        HttpTransport transport = new HttpTransport(base, token);
        this.queue = new VipWebQueue(transport, new VipAcks(db), commands, line -> plugin.getLogger().info(line));
        // Same cap as the in-game GUI (VipGui.playerLimit): rank's players, VIP default when unknown.
        this.sync = new VipWorldSync(store, transport, owner -> {
            int limit = config.players(rank(owner));
            return limit > 0 ? limit : config.vipPlayers();
        });
        plugin.getLogger().info("VIP-Queue: Polling aktiv (" + base + ")");
        schedule();
    }

    public static String rank(UUID player) {
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player);
            if (user == null) user = LuckPermsProvider.get().getUserManager().loadUser(player).join();
            if (user == null) return "";
            return VipLimits.rank(user.getInheritedGroups(QueryOptions.nonContextual()).stream()
                .map(g -> g.getName()).toList());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void schedule() {
        if (stopped || queue == null) return;
        try {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, this::poll, POLL_MILLIS / 50L);
        } catch (IllegalStateException disabling) {
            stopped = true;
        }
    }

    private void poll() {
        if (stopped) return;
        try { queue.pollOnce(System.currentTimeMillis()); }
        catch (Exception e) { plugin.getLogger().warning("VIP-Queue: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage())); }
        // The panel only shows what this sync publishes; a failed poll must not block it.
        try { sync.maybeSend(System.currentTimeMillis()); }
        catch (Exception e) { plugin.getLogger().warning("VIP-Sync: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage())); }
        finally { schedule(); }
    }

    public void shutdown() { stopped = true; }

    /** Minimaler HTTP-Client; Basis-URL endet ohne Schraegstrich, Token geht als Header mit. */
    static final class HttpTransport implements VipWebQueue.Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final String base, token;
        HttpTransport(String base, String token) { this.base = base; this.token = token; }

        @Override public String get(String path) throws Exception {
            HttpResponse<String> r = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IllegalStateException("poll HTTP " + r.statusCode());
            return r.body();
        }

        @Override public int post(String path, String body) throws Exception {
            return client.send(request(path).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("x-ingest-token", token);
        }
    }

    static Consumer<String> logger(JavaPlugin plugin) { return line -> plugin.getLogger().info(line); }
}
