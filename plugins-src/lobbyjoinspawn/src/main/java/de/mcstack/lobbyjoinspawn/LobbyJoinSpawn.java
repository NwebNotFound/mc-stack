package de.mcstack.lobbyjoinspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

public final class LobbyJoinSpawn extends JavaPlugin implements Listener {
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private Location spawn() {
        World world = Bukkit.getWorlds().get(0);
        Plugin craft = Bukkit.getPluginManager().getPlugin("CraftCore");
        if (craft instanceof JavaPlugin core) {
            ConfigurationSection s = core.getConfig().getConfigurationSection("spawn");
            if (s != null && s.isString("world") && s.get("x") instanceof Number && s.get("y") instanceof Number && s.get("z") instanceof Number) {
                World configured = Bukkit.getWorld(s.getString("world"));
                if (configured != null) {
                    return new Location(configured, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                            (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
                }
                getLogger().warning("CraftCore-Spawnwelt nicht geladen; Welt-Spawn wird verwendet.");
            }
        }
        Location result = world.getSpawnLocation();
        FileConfiguration saved = getConfig();
        if (world.getName().equals(saved.getString("world"))
                && result.getBlockX() == saved.getInt("x") && result.getBlockY() == saved.getInt("y")
                && result.getBlockZ() == saved.getInt("z") && result.getYaw() == (float) saved.getDouble("yaw")) {
            result.setPitch((float) saved.getDouble("pitch"));
        }
        return result;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(PlayerSpawnLocationEvent event) { event.setSpawnLocation(spawn()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !player.teleport(spawn())) {
                getLogger().warning("Lobby-Spawn-Teleport fehlgeschlagen: " + player.getName());
            }
        }, 1L);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Nur Spieler koennen den Spawn setzen."); return true; }
        if (!player.isOp()) { sender.sendMessage("Nur OPs koennen den Spawn setzen."); return true; }
        Location loc = player.getLocation();
        if (!loc.getWorld().equals(Bukkit.getWorlds().get(0))) {
            sender.sendMessage("Bitte den Spawn in der Lobby-Hauptwelt setzen."); return true;
        }
        if (!loc.getWorld().setSpawnLocation(loc)) { sender.sendMessage("Spawn konnte nicht gesetzt werden."); return true; }
        Location actual = loc.getWorld().getSpawnLocation();
        getConfig().set("world", actual.getWorld().getName());
        getConfig().set("x", actual.getBlockX()); getConfig().set("y", actual.getBlockY());
        getConfig().set("z", actual.getBlockZ()); getConfig().set("yaw", (double) actual.getYaw());
        getConfig().set("pitch", (double) loc.getPitch()); saveConfig();
        Plugin craft = Bukkit.getPluginManager().getPlugin("CraftCore");
        if (craft instanceof JavaPlugin core && core.getConfig().isConfigurationSection("spawn")) {
            ConfigurationSection s = core.getConfig().getConfigurationSection("spawn");
            s.set("world", actual.getWorld().getName()); s.set("x", actual.getX()); s.set("y", actual.getY());
            s.set("z", actual.getZ()); s.set("yaw", (double) actual.getYaw()); s.set("pitch", (double) loc.getPitch());
            core.saveConfig();
        }
        sender.sendMessage("Lobby-Spawn gesetzt."); return true;
    }
}
