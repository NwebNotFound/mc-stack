package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps vip_worlds.status true to the host. A VIP world server shuts itself down when it is idle (VipIdleStop), and a
 * crash, a host restart or a container stopped by hand end it too; nothing wrote that back, so the row stayed
 * "running" until someone pressed Beitreten/Stopp in the lobby, and /vip showed a running world that could not be
 * started (Homelab Deployer, 08.11.2026: two such rows). The lobby now checks once shortly after start and then every
 * minute: a world the host runs nowhere becomes "stopped" after two passes in a row (VipReconcile.plan), a world on
 * another slot gets its real slot. Host unreachable: no change. One /vip/status request per pass, and only when some
 * row claims to run.
 */
public final class VipStatusReconciler {
    static final long FIRST_TICKS = 20L * 30, PERIOD_TICKS = 20L * 60;

    /** The database side, so the pass can be tested without Bukkit or a host. */
    interface Store {
        List<VipReconcile.Row> active() throws Exception;
        void stopped(String id) throws Exception;
        void status(String id, String status, String slot) throws Exception;
    }

    private final JavaPlugin plugin;
    private final Store store;
    private final Supplier<List<VipReconcile.Slot>> host;
    private Set<String> missing = Set.of();
    private BukkitTask task;

    public VipStatusReconciler(JavaPlugin plugin, VipStore store, VipHostClient host) {
        this(plugin, new Store() {
            public List<VipReconcile.Row> active() throws Exception { return store.active(); }
            public void stopped(String id) throws Exception { store.idleStopped(id); }
            public void status(String id, String status, String slot) throws Exception { store.status(id, status, slot); }
        }, host::slots);
    }

    VipStatusReconciler(JavaPlugin plugin, Store store, Supplier<List<VipReconcile.Slot>> host) {
        this.plugin = plugin; this.store = store; this.host = host;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, FIRST_TICKS, PERIOD_TICKS);
    }

    public void shutdown() { if (task != null) task.cancel(); }

    /** One pass; returns how many rows it changed. Never throws (a failed pass just waits for the next one). */
    synchronized int tick() {
        try {
            List<VipReconcile.Row> rows = store.active();
            if (rows.isEmpty()) { missing = Set.of(); return 0; }
            VipReconcile.Plan plan = VipReconcile.plan(rows, host.get(), missing);
            missing = plan.missing();
            for (VipReconcile.Update u : plan.updates()) {
                switch (u.kind()) {
                    case STOPPED -> store.stopped(u.id());
                    case RUNNING -> store.status(u.id(), "running", u.slot());
                    case STARTING -> store.status(u.id(), "starting", u.slot());
                    default -> { }
                }
            }
            if (!plan.updates().isEmpty() && plugin != null)
                plugin.getLogger().info("VIP-Status: " + plan.updates().size() + " Welt(en) an den Host angeglichen.");
            return plan.updates().size();
        } catch (Exception e) {
            if (plugin != null) plugin.getLogger().warning("VIP-Status-Abgleich: " + e.getClass().getSimpleName());
            return 0;
        }
    }
}
