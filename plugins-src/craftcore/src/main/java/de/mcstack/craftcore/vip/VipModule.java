package de.mcstack.craftcore.vip;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds the coordinator, the bars, and the GUI for one server. */
public final class VipModule {
    private final VipGui gui;
    private final VipStartCoordinator coordinator;
    private final VipStartBars bars;

    public VipModule(JavaPlugin plugin, VipStore.ConnectionSource source, VipGui.Connector connector,
                     boolean inWorld, String worldId) {
        VipConfig.Values config = VipConfig.load();
        VipHostClient host = new VipHostClient();
        VipStore store = new VipStore(source);
        this.coordinator = new VipStartCoordinator(
            new VipStartCoordinator.Host() {
                public VipStartCoordinator.Reply start(String id) { return reply(host.start(id)); }
                public VipStartCoordinator.Reply status(String slot) { return reply(host.status(slot)); }
                public void stop(String id, String slot) { host.stop(slot, id); }
            },
            // poll() does blocking HTTP, so the timer must never run on the server thread
            (delayMillis, task) -> Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(1, delayMillis / 50)),
            runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable),
            System::currentTimeMillis,
            config.pollSeconds() * 1000L,
            config.startTimeoutSeconds() * 1000L,
            config.maxErrors(),
            new VipStartCoordinator.Events() {
                public void ready(String id, String slot) {
                    background(plugin, () -> {
                        try { store.started(id, slot); }
                        catch (Exception e) { plugin.getLogger().warning("VIP-Start: " + e.getClass().getSimpleName()); }
                    });
                }
                public void finished(String id, String reason, String slot, boolean began) {
                    background(plugin, () -> {
                        // A start that already reached the host must not leave the slot running, whatever the
                        // reason (cancel, timeout, host unavailable): otherwise the slot stays held until idle stop.
                        if (began && slot != null) host.stop(slot, id);
                        try { store.status(id, "stopped", null); }
                        catch (Exception e) { plugin.getLogger().warning("VIP-Startabbruch: " + e.getClass().getSimpleName()); }
                    });
                }
            });
        this.bars = new VipStartBars(plugin, coordinator);
        this.gui = new VipGui(plugin, source, coordinator, bars, config, connector, inWorld, worldId);
    }

    /** The coordinator reports from the server thread or from a poll thread; both end up async. */
    private static void background(JavaPlugin plugin, Runnable task) {
        try { Bukkit.getScheduler().runTaskAsynchronously(plugin, task); }
        catch (IllegalStateException disabling) { task.run(); }
    }

    public VipGui gui() { return gui; }

    public void start() { gui.start(); }

    public void shutdown() {
        coordinator.shutdown();
        bars.shutdown();
    }

    static VipStartCoordinator.Reply reply(VipHostClient.Result result) {
        if (result == null) return VipStartCoordinator.Reply.failed(VipStartCoordinator.START_FAILED);
        return switch (result.outcome()) {
            case ONLINE -> VipStartCoordinator.Reply.online(result.slot());
            case STARTING -> VipStartCoordinator.Reply.starting(result.slot());
            case NO_FREE_SLOT -> VipStartCoordinator.Reply.failed(VipStartCoordinator.NO_FREE_SLOT);
            case RAM_BUDGET -> VipStartCoordinator.Reply.failed(VipStartCoordinator.RAM_BUDGET);
            case WORLD_NOT_FOUND -> VipStartCoordinator.Reply.failed(VipStartCoordinator.WORLD_NOT_FOUND);
            default -> VipStartCoordinator.Reply.failed(VipStartCoordinator.START_FAILED);
        };
    }
}
