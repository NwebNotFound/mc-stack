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
        this.coordinator = new VipStartCoordinator(
            new VipStartCoordinator.Host() {
                public VipStartCoordinator.Reply start(String id) { return reply(host.start(id)); }
                public VipStartCoordinator.Reply status(String slot) { return reply(host.status(slot)); }
            },
            (delayMillis, task) -> Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1, delayMillis / 50)),
            runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable),
            System::currentTimeMillis,
            config.pollSeconds() * 1000L,
            config.startTimeoutSeconds() * 1000L,
            config.maxErrors());
        this.bars = new VipStartBars(plugin, coordinator);
        this.gui = new VipGui(plugin, source, coordinator, bars, config, connector, inWorld, worldId);
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
