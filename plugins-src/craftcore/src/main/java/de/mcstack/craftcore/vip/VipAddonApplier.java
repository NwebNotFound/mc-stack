package de.mcstack.craftcore.vip;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Applies the selected addons of this world to the loaded world and keeps them in sync. */
public final class VipAddonApplier implements Listener {
    private final JavaPlugin plugin;
    private final VipStore store;
    private final String worldId;
    /** Players who are only in creative because of the addon, so the mode can be given back. */
    private final Set<UUID> creativeGranted = new HashSet<>();
    private volatile List<String> selected = List.of();
    /** Difficulty before the peaceful addon took over; null while it is off. */
    private Difficulty beforePeaceful;

    public VipAddonApplier(JavaPlugin plugin, VipStore.ConnectionSource source) {
        this.plugin = plugin;
        this.store = new VipStore(source);
        this.worldId = System.getenv("CRAFTCORE_VIP_WORLD_ID");
    }

    public void start() {
        if (worldId == null || worldId.isBlank()) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        refresh();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, 100L);
    }

    private void refresh() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> addons;
            try { addons = VipAddons.sanitize(store.addons(worldId)); }
            catch (Exception e) { return; }
            Bukkit.getScheduler().runTask(plugin, () -> apply(addons));
        });
    }

    private void apply(List<String> addons) {
        selected = addons;
        if (Bukkit.getWorlds().isEmpty()) return;
        World world = Bukkit.getWorlds().get(0);
        boolean peaceful = addons.contains("peaceful");
        world.setGameRule(GameRule.KEEP_INVENTORY, addons.contains("keep-inventory"));
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, !addons.contains("day-lock"));
        world.setGameRule(GameRule.DO_MOB_SPAWNING, !peaceful);
        world.setPVP(addons.contains("pvp"));
        Difficulty current = world.getDifficulty();
        if (peaceful && current != Difficulty.PEACEFUL) beforePeaceful = current;
        world.setDifficulty(VipAddonRules.difficulty(peaceful, beforePeaceful, current));
        if (!peaceful) beforePeaceful = null;
        if (addons.contains("day-lock")) world.setTime(6000L);
        applyGameMode(world, addons.contains("creative"));
    }

    private void applyGameMode(World world, boolean creative) {
        for (Player player : world.getPlayers()) {
            UUID id = player.getUniqueId();
            GameMode next = VipAddonRules.mode(creative, creativeGranted.contains(id), player.getGameMode());
            if (next != null) player.setGameMode(next);
            if (creative) creativeGranted.add(id);
            else creativeGranted.remove(id);
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (creativeGranted.contains(id)) return;
        GameMode next = VipAddonRules.mode(selected.contains("creative"), false, player.getGameMode());
        if (next != null) player.setGameMode(next);
        if (selected.contains("creative")) creativeGranted.add(id);
    }
}
