package de.mcstack.craftcore.vip;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    /** Game mode a player had before the addon handed out creative. */
    private final Map<UUID, GameMode> modeBeforeGrant = new HashMap<>();
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
        VipAddonRules.Effects effects = VipAddonRules.world(addons);
        boolean peaceful = addons.contains("peaceful");
        world.setGameRule(GameRule.KEEP_INVENTORY, effects.keepInventory());
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, effects.daylightCycle());
        world.setGameRule(GameRule.DO_MOB_SPAWNING, effects.mobSpawning());
        world.setPVP(effects.pvp());
        Difficulty current = world.getDifficulty();
        if (peaceful && current != Difficulty.PEACEFUL) beforePeaceful = current;
        world.setDifficulty(VipAddonRules.difficulty(peaceful, beforePeaceful, current));
        if (!peaceful) beforePeaceful = null;
        if (effects.pinNoon()) world.setTime(6000L);
        applyGameMode(world, addons.contains("creative"));
    }

    /**
     * Hands creative out once per player instead of forcing it every tick: the addon used to reset
     * every other game mode, which is why {@code /gamemode} had no effect while it was active.
     */
    private void applyGameMode(World world, boolean creative) {
        for (Player player : world.getPlayers()) {
            UUID id = player.getUniqueId();
            if (creative) {
                GameMode next = VipAddonRules.grant(true, creativeGranted.contains(id), player.getGameMode());
                if (next != null) {
                    modeBeforeGrant.put(id, player.getGameMode());
                    player.setGameMode(next);
                }
                creativeGranted.add(id);
                continue;
            }
            if (!creativeGranted.remove(id)) continue;
            GameMode next = VipAddonRules.revoke(true, player.getGameMode(), modeBeforeGrant.remove(id));
            if (next != null) player.setGameMode(next);
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (creativeGranted.contains(id)) return;
        GameMode next = VipAddonRules.grant(selected.contains("creative"), false, player.getGameMode());
        if (next == null) return;
        modeBeforeGrant.put(id, player.getGameMode());
        player.setGameMode(next);
        creativeGranted.add(id);
    }
}
