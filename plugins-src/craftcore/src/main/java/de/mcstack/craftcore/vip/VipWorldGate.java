package de.mcstack.craftcore.vip;

import de.mcstack.craftcore.pvp.PvpAccess;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class VipWorldGate implements Listener {
    private final JavaPlugin plugin;
    private final VipStore.ConnectionSource source;
    private final UUID worldId;
    private final VipConfig.Values config = VipConfig.load();
    private record Decision(boolean allowed, boolean owner, int limit, long createdAt) {}
    private final Map<UUID, Decision> decisions = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "vip-zugang"); t.setDaemon(true); return t; });

    public VipWorldGate(JavaPlugin plugin, VipStore.ConnectionSource source, String id) {
        this.plugin=plugin; this.source=source;
        UUID parsed=null;
        try { if (id != null && id.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) parsed=UUID.fromString(id); }
        catch (IllegalArgumentException ignored) { }
        worldId=parsed;
    }
    public void start() {
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> decisions.entrySet().removeIf(e -> System.nanoTime()-e.getValue().createdAt()>TimeUnit.MINUTES.toNanos(1)), 1200L, 1200L);
    }
    public void stop() { executor.shutdownNow(); decisions.clear(); }

    @EventHandler(priority=EventPriority.MONITOR)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        decisions.remove(event.getUniqueId());
        if (event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        Decision decision=new Decision(false,false,0,System.nanoTime());
        if (worldId!=null) {
            Future<Decision> future=null;
            try {
                future=executor.submit(() -> lookup(event.getUniqueId()));
                decision=future.get(3,TimeUnit.SECONDS);
            } catch (Exception ex) {
                if (future!=null) future.cancel(true);
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                plugin.getLogger().warning("VIP-Welt-Zugangspruefung fehlgeschlagen: "+ex.getClass().getSimpleName());
            }
        }
        decisions.put(event.getUniqueId(),new Decision(decision.allowed(),decision.owner(),decision.limit(),System.nanoTime()));
    }
    private Decision lookup(UUID player) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(
                "SELECT owner, EXISTS(SELECT 1 FROM vip_world_members m WHERE m.world_id=w.id AND m.player=?) FROM vip_worlds w WHERE w.id=? AND w.status<>'deleted'")) {
            s.setQueryTimeout(2); s.setString(1,player.toString()); s.setString(2,worldId.toString());
            try (ResultSet r=s.executeQuery()) {
                if (!r.next()) return new Decision(false,false,0,System.nanoTime());
                boolean owner=player.toString().equalsIgnoreCase(r.getString(1));
                boolean member=r.getBoolean(2);
                int limit=config.players(VipPerks.rank(UUID.fromString(r.getString(1))));
                if (limit<=0) limit=config.vipPlayers();
                return new Decision(VipAccessRules.decide(owner,member,false,true,true),owner,limit,System.nanoTime());
            }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void login(PlayerLoginEvent event) {
        Decision decision=decisions.remove(event.getPlayer().getUniqueId());
        boolean bypass=event.getPlayer().hasPermission("craftcore.vip.bypass")
            || event.getPlayer().hasPermission("craftcore.vip.admin")
            || event.getPlayer().hasPermission("group.admin")
            || PvpAccess.mayBypass(event.getPlayer()::hasPermission);
        if (decision==null || System.nanoTime()-decision.createdAt()>TimeUnit.MINUTES.toNanos(1)) {
            if (!bypass) event.disallow(PlayerLoginEvent.Result.KICK_OTHER,"Du hast keinen Zugang zu dieser VIP-Welt.");
            return;
        }
        if (!decision.allowed() && !bypass) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,"Du hast keinen Zugang zu dieser VIP-Welt.");
            return;
        }
        if (!bypass && !decision.owner() && VipLimits.atCap(Bukkit.getOnlinePlayers().size(),decision.limit())) {
            event.disallow(PlayerLoginEvent.Result.KICK_FULL,"Die VIP-Welt ist voll ("+decision.limit()+" Spieler).");
        }
    }
}
