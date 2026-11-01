package de.mcstack.craftcore;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import org.bukkit.event.player.PlayerTeleportEvent;

final class EndLock implements Listener {
  private static final String DEFAULT_MESSAGE = "&cDas End ist noch gesperrt - es wird später als Event freigeschaltet!";
  private final CraftCore core;
  private final EndLockRules rules = new EndLockRules();
  EndLock(CraftCore core) { this.core = core; }
  private boolean enabled() { return core.getConfig().getBoolean("end-lock.enabled", true); }
  private boolean blocked(Player player) {
    return EndLockRules.blocked(enabled(), player.isOp(), player.hasPermission("craftcore.end.bypass"));
  }
  private void notify(Player player) {
    if (rules.shouldNotify(player.getUniqueId(), System.currentTimeMillis()))
      player.sendMessage(ChatColor.translateAlternateColorCodes('&', core.getConfig().getString("end-lock.message", DEFAULT_MESSAGE)));
  }
  private static boolean end(org.bukkit.Location location) {
    return location != null && location.getWorld() != null && location.getWorld().getEnvironment() == World.Environment.THE_END;
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void eye(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getItem() == null
        || event.getItem().getType() != Material.ENDER_EYE || !(event.getClickedBlock().getBlockData() instanceof EndPortalFrame frame)
        || frame.hasEye() || !blocked(event.getPlayer())) return;
    event.setCancelled(true);
    notify(event.getPlayer());
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void portal(PlayerPortalEvent event) {
    if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL && blocked(event.getPlayer())) {
      event.setCancelled(true);
      notify(event.getPlayer());
    }
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent event) {
    if (end(event.getTo()) && blocked(event.getPlayer())) {
      event.setCancelled(true);
      notify(event.getPlayer());
    }
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityPortal(EntityPortalEvent event) {
    if (!enabled() || event.getPortalType() != PortalType.ENDER) return;
    if (event.getEntity() instanceof Player player) {
      if (!blocked(player)) return;
      notify(player);
    }
    event.setCancelled(true);
  }

}
