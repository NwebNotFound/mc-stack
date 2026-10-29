package de.mcstack.craftcore;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.concurrent.ExecutionException;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

final class SafeSpawn implements Listener {
  private final CraftCore plugin;
  SafeSpawn(CraftCore plugin) { this.plugin = plugin; }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void initial(AsyncPlayerSpawnLocationEvent event) {
    if (!event.isNewPlayer()) return;
    try {
      // World and block access belongs on the server thread, not this asynchronous event.
      event.setSpawnLocation(Bukkit.getScheduler().callSyncMethod(plugin, this::choose).get());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      plugin.getLogger().warning("Zufallsspawn unterbrochen: " + ex);
    } catch (ExecutionException ex) {
      plugin.getLogger().warning("Zufallsspawn fehlgeschlagen: " + ex.getCause());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void respawn(PlayerRespawnEvent event) {
    if (!event.isBedSpawn() && !event.isAnchorSpawn()) event.setRespawnLocation(choose());
  }

  private Location choose() {
    World world = Bukkit.getWorlds().get(0);
    Location spawn = world.getSpawnLocation();
    int sx = spawn.getBlockX(), sz = spawn.getBlockZ();
    return SafeSpawnSearch.find(SafeSpawnSearch::randomCoordinate, (dx, dz) -> {
      int x = sx + dx, z = sz + dz;
      int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
      if (y < world.getMinHeight() || y + 2 >= world.getMaxHeight()) return false;
      Block ground = world.getBlockAt(x, y, z);
      boolean waterlogged = ground.getBlockData() instanceof Waterlogged water && water.isWaterlogged();
      Location feet = new Location(world, x + 0.5, y + 1, z + 0.5);
      return world.getWorldBorder().isInside(feet) && SafeSpawnSearch.safeBlocks(
          ground.getType().isSolid(), Tag.LEAVES.isTagged(ground.getType()), ground.isLiquid(), waterlogged,
          world.getBlockAt(x, y + 1, z).getType().isAir(), world.getBlockAt(x, y + 2, z).getType().isAir());
    }).map(offset -> {
      int x = sx + offset.x(), z = sz + offset.z();
      return new Location(world, x + 0.5, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5, spawn.getYaw(), spawn.getPitch());
    }).orElse(spawn);
  }
}
