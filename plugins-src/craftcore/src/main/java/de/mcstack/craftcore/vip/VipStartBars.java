package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** One boss bar per waiting player, driven by the coordinator progress. */
final class VipStartBars {
    private final JavaPlugin plugin;
    private final VipStartCoordinator coordinator;
    private final Map<UUID, String> worlds = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    VipStartBars(JavaPlugin plugin, VipStartCoordinator coordinator) {
        this.plugin = plugin;
        this.coordinator = coordinator;
    }

    void wait(Player player, String worldId) {
        worlds.put(player.getUniqueId(), worldId);
        if (Bukkit.isPrimaryThread()) update(player);
        else Bukkit.getScheduler().runTask(plugin, () -> update(player));
    }

    void clear(UUID id) {
        worlds.remove(id);
        BossBar bar = bars.remove(id);
        if (bar == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Bukkit.isPrimaryThread()) player.hideBossBar(bar);
        else Bukkit.getScheduler().runTask(plugin, () -> player.hideBossBar(bar));
    }

    void tick() {
        for (Map.Entry<UUID, String> entry : List.copyOf(worlds.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) { clear(entry.getKey()); continue; }
            update(player);
        }
    }

    void shutdown() { for (UUID id : List.copyOf(worlds.keySet())) clear(id); }

    private void update(Player player) {
        String worldId = worlds.get(player.getUniqueId());
        if (worldId == null) return;
        var progress = coordinator.progress(worldId);
        if (progress.isEmpty()) { clear(player.getUniqueId()); return; }
        var update = VipStartProgress.starting(progress.get().slot(), progress.get().elapsedMillis(), progress.get().timeoutMillis());
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> {
            BossBar created = BossBar.bossBar(Component.text(update.title(), NamedTextColor.GOLD),
                update.fraction(), BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            player.showBossBar(created);
            return created;
        });
        bar.name(Component.text(update.title(), NamedTextColor.GOLD));
        bar.progress(update.fraction());
    }
}
