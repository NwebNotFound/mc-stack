package de.mcstack.craftcore.vip;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies the selected addons of this world to the loaded world and keeps them in sync.
 *
 * <p>The creative grant lives in the player's persistent data container: a restart or a relog
 * neither loses the mark nor leaves a player in creative after the addon was switched off.</p>
 */
public final class VipAddonApplier implements Listener {
    /** Set when the addon handed creative out; survives a server restart. */
    static final NamespacedKey CREATIVE_GRANTED = key("craftcore:vip_creative_granted");
    /** Game mode the player had before the grant. */
    static final NamespacedKey MODE_BEFORE = key("craftcore:vip_creative_before");

    private final JavaPlugin plugin;
    private final VipStore store;
    private final String worldId;
    private volatile List<String> selected = List.of();
    /** Difficulty before the peaceful addon took over; null while it is off. */
    private Difficulty beforePeaceful;

    public VipAddonApplier(JavaPlugin plugin, VipStore.ConnectionSource source) {
        this.plugin = plugin;
        this.store = new VipStore(source);
        this.worldId = System.getenv("CRAFTCORE_VIP_WORLD_ID");
    }

    private static NamespacedKey key(String name) {
        NamespacedKey key = NamespacedKey.fromString(name);
        if (key == null) throw new IllegalStateException("ungueltiger Key: " + name);
        return key;
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
        applyGameMode(world.getPlayers(), addons.contains("creative"));
    }

    /**
     * Grants or hands back the addon game mode. The old code forced creative on every pass, which is
     * why {@code /gamemode} had no effect while the addon was active.
     */
    private void applyGameMode(List<Player> players, boolean creative) {
        for (Player player : players) applyGameMode(player, creative);
    }

    void applyGameMode(Player player, boolean creative) {
        GameMode current = player.getGameMode();
        VipAddonRules.Mode decision = VipAddonRules.onJoin(creative, granted(player), current, before(player));
        if (decision.change() && decision.target() != null) player.setGameMode(decision.target());
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (decision.mark()) {
            data.set(CREATIVE_GRANTED, PersistentDataType.BYTE, (byte) 1);
            data.set(MODE_BEFORE, PersistentDataType.STRING, current.name());
        }
        if (decision.clear()) {
            data.remove(CREATIVE_GRANTED);
            data.remove(MODE_BEFORE);
        }
    }

    private static boolean granted(Player player) {
        Byte value = player.getPersistentDataContainer().get(CREATIVE_GRANTED, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    private static GameMode before(Player player) {
        String name = player.getPersistentDataContainer().get(MODE_BEFORE, PersistentDataType.STRING);
        if (name == null) return null;
        try { return GameMode.valueOf(name.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        applyGameMode(event.getPlayer(), selected.contains("creative"));
    }
}
