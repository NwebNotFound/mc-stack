package de.mcstack.craftcore;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

final class LaunchPads implements Listener, CommandExecutor {
  private final CraftCore core;
  private final NamespacedKey marker, saved;
  private final Map<UUID, Flight> flights = new HashMap<>();
  private final Map<UUID, Long> cooldowns = new HashMap<>(), fallUntil = new HashMap<>();
  private List<LaunchPadLogic.Pad> pads = List.of();
  private boolean enabled;
  private long cooldown;
  private static final long GRACE_MS = 3000;
  private static final class Flight {
    final LaunchPadLogic.Pad pad;
    int ticks;
    boolean equipped, boosting = true;
    Flight(LaunchPadLogic.Pad pad) { this.pad = pad; }
  }
  LaunchPads(CraftCore core) { this.core=core; marker=new NamespacedKey(core,"launch_elytra"); saved=new NamespacedKey(core,"launch_chestplate"); }
  void start() {
    load();
    Bukkit.getPluginManager().registerEvents(this, core);
    Objects.requireNonNull(core.getCommand("cclaunch")).setExecutor(this);
    Bukkit.getOnlinePlayers().forEach(this::restore);
    Bukkit.getScheduler().runTaskTimer(core, this::tick, 1L, 1L);
  }
  private static double number(Map<?, ?> map, String key, double fallback) {
    Object value=map.get(key); return value instanceof Number n ? n.doubleValue() : fallback;
  }
  void load() {
    enabled=core.getConfig().getBoolean("launchpads.enabled", true);
    cooldown=Math.max(0, core.getConfig().getLong("launchpads.cooldown-seconds", 3))*1000L;
    List<LaunchPadLogic.Pad> loaded=new ArrayList<>();
    for (Map<?, ?> m:core.getConfig().getMapList("launchpads.pads")) {
      if (!(m.get("id") instanceof String id) || !(m.get("world") instanceof String world) || !(m.get("target") instanceof Map<?, ?> target)
          || !(m.get("x") instanceof Number) || !(m.get("y") instanceof Number) || !(m.get("z") instanceof Number)
          || !(target.get("x") instanceof Number) || !(target.get("y") instanceof Number) || !(target.get("z") instanceof Number)) {
        core.getLogger().warning("Invalid launch pad: " + m); continue;
      }
      double height=number(m,"launch-height",5), speed=number(m,"boost-speed",1.6);
      if (!Double.isFinite(height) || !Double.isFinite(speed) || height <= 0 || speed <= 0) { core.getLogger().warning("Invalid launch pad height or speed: " + id); continue; }
      loaded.add(new LaunchPadLogic.Pad(id,world,((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue(),number(target,"x",0),number(target,"y",0),number(target,"z",0),height,speed));
    }
    pads=List.copyOf(loaded);
  }
  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("craftcore.launch.admin")) { sender.sendMessage("Keine Berechtigung."); return true; }
    if (args.length != 1) return false;
    if (args[0].equalsIgnoreCase("reload")) { core.reloadConfig(); load(); sender.sendMessage("LaunchPads neu geladen."); return true; }
    if (args[0].equalsIgnoreCase("list")) {
      sender.sendMessage("LaunchPads: " + (enabled ? "aktiv" : "deaktiviert"));
      for (var p:pads) sender.sendMessage(p.id()+": "+p.world()+" "+p.x()+","+p.y()+","+p.z()+" -> "+p.targetX()+","+p.targetY()+","+p.targetZ());
      return true;
    }
    return false;
  }
  @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e) {
    Player player=e.getPlayer();
    if (!enabled || e.getTo()==null || flights.containsKey(player.getUniqueId())) return;
    long now=System.currentTimeMillis(); Long last=cooldowns.get(player.getUniqueId());
    if (last!=null && !LaunchPadLogic.ready(now,last,cooldown)) return;
    Location l=e.getTo();
    for (var pad:pads) if (pad.matches(l.getWorld().getName(),l.getX(),l.getY(),l.getZ())) {
      cooldowns.put(player.getUniqueId(),now); fallUntil.remove(player.getUniqueId());
      flights.put(player.getUniqueId(),new Flight(pad));
      player.setVelocity(new Vector(0,LaunchPadLogic.upwardVelocity(pad.height()),0));
      break;
    }
  }
  private void tick() {
    long now=System.currentTimeMillis();
    for (UUID id:new ArrayList<>(fallUntil.keySet())) {
      Player p=Bukkit.getPlayer(id); long until=fallUntil.get(id);
      if (p==null || (until!=Long.MAX_VALUE && now>until)) fallUntil.remove(id);
      else if (until==Long.MAX_VALUE && p.isOnGround()) fallUntil.put(id,now+GRACE_MS);
    }
    for (UUID id:new ArrayList<>(flights.keySet())) {
      Flight f=flights.get(id); Player p=Bukkit.getPlayer(id);
      if (f==null || p==null) continue;
      f.ticks++;
      if (f.ticks==10) {
        if (p.isOnGround()) { finish(p); continue; }
        ItemStack chest=p.getInventory().getChestplate();
        p.getPersistentDataContainer().set(saved,PersistentDataType.BYTE_ARRAY, chest==null || chest.getType().isAir() ? new byte[0] : chest.serializeAsBytes());
        p.saveData();
        ItemStack elytra=new ItemStack(Material.ELYTRA);
        var meta=elytra.getItemMeta(); meta.getPersistentDataContainer().set(marker,PersistentDataType.BYTE,(byte)1); meta.setUnbreakable(true); elytra.setItemMeta(meta);
        p.getInventory().setChestplate(elytra); f.equipped=true; p.saveData(); p.setGliding(true);
      }
      if (f.ticks>10 && p.isOnGround()) { finish(p); continue; }
      if (f.equipped && f.boosting && f.ticks%4==0) {
        Location l=p.getLocation(); double dx=f.pad.targetX()-l.getX(), dy=f.pad.targetY()-l.getY(), dz=f.pad.targetZ()-l.getZ();
        if (dx*dx+dy*dy+dz*dz<=25 || dx*dx+dz*dz<=16) { f.boosting=false; continue; }
        double[] v=LaunchPadLogic.boost(dx,dy,dz,f.pad.speed()); p.setVelocity(new Vector(v[0],v[1],v[2]));
      }
    }
  }
  private boolean marked(ItemStack item) { return item!=null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(marker,PersistentDataType.BYTE); }
  private void removeMarked(Player p) {
    var inv=p.getInventory();
    for (int i=0;i<inv.getSize();i++) if (marked(inv.getItem(i))) inv.setItem(i,null);
    if (marked(p.getItemOnCursor())) p.setItemOnCursor(null);
  }
  private ItemStack savedChest(Player p) {
    byte[] bytes=p.getPersistentDataContainer().get(saved,PersistentDataType.BYTE_ARRAY);
    return bytes==null || bytes.length==0 ? null : ItemStack.deserializeBytes(bytes);
  }
  private void restore(Player p) {
    removeMarked(p);
    if (!p.getPersistentDataContainer().has(saved,PersistentDataType.BYTE_ARRAY)) return;
    ItemStack chest=savedChest(p);
    if (chest!=null && !chest.equals(p.getInventory().getChestplate())) {
      if (p.getInventory().getChestplate()==null || p.getInventory().getChestplate().getType().isAir()) p.getInventory().setChestplate(chest);
      else for (ItemStack overflow:p.getInventory().addItem(chest).values()) p.getWorld().dropItemNaturally(p.getLocation(),overflow);
    }
    p.getPersistentDataContainer().remove(saved); p.saveData();
  }
  private void finish(Player p) {
    if (flights.remove(p.getUniqueId())==null) return;
    fallUntil.put(p.getUniqueId(),p.isOnGround() ? System.currentTimeMillis()+GRACE_MS : Long.MAX_VALUE);
    restore(p);
  }
  void shutdown() { for (UUID id:new ArrayList<>(flights.keySet())) { Player p=Bukkit.getPlayer(id); if (p!=null) finish(p); } }
  @EventHandler public void join(PlayerJoinEvent e) { restore(e.getPlayer()); }
  @EventHandler public void quit(PlayerQuitEvent e) { finish(e.getPlayer()); cooldowns.remove(e.getPlayer().getUniqueId()); fallUntil.remove(e.getPlayer().getUniqueId()); }
  @EventHandler public void kick(PlayerKickEvent e) { if (!e.isCancelled()) finish(e.getPlayer()); }
  @EventHandler public void world(PlayerChangedWorldEvent e) { finish(e.getPlayer()); }
  @EventHandler(ignoreCancelled=true) public void glide(EntityToggleGlideEvent e) { if (e.getEntity() instanceof Player p && !e.isGliding()) { Flight f=flights.get(p.getUniqueId()); if (f!=null && f.equipped) finish(p); } }
  @EventHandler(ignoreCancelled=true) public void damage(EntityDamageEvent e) {
    if (!(e.getEntity() instanceof Player p) || e.getCause()!=EntityDamageEvent.DamageCause.FALL) return;
    Long until=fallUntil.get(p.getUniqueId());
    if (flights.containsKey(p.getUniqueId()) || (until!=null && System.currentTimeMillis()<=until)) { e.setCancelled(true); p.setFallDistance(0); }
    else fallUntil.remove(p.getUniqueId());
  }
  @EventHandler public void death(PlayerDeathEvent e) {
    Player p=e.getEntity(); boolean active=flights.remove(p.getUniqueId())!=null;
    e.getDrops().removeIf(this::marked); removeMarked(p);
    if (p.getPersistentDataContainer().has(saved,PersistentDataType.BYTE_ARRAY)) {
      if (e.getKeepInventory()) restore(p);
      else { ItemStack chest=savedChest(p); if (chest!=null) e.getDrops().add(chest); p.getPersistentDataContainer().remove(saved); }
    }
    if (active) fallUntil.remove(p.getUniqueId());
  }
  @EventHandler(ignoreCancelled=true) public void click(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (flights.containsKey(p.getUniqueId()) || marked(e.getCurrentItem()) || marked(e.getCursor()) || (e.getHotbarButton()>=0 && marked(p.getInventory().getItem(e.getHotbarButton())))
        || (flights.containsKey(p.getUniqueId()) && e.getClickedInventory()==p.getInventory() && e.getSlot()==38)) e.setCancelled(true);
  }
  @EventHandler(ignoreCancelled=true) public void drag(InventoryDragEvent e) {
    if (marked(e.getOldCursor()) || (e.getWhoClicked() instanceof Player p && flights.containsKey(p.getUniqueId()))) e.setCancelled(true);
  }
  @EventHandler(ignoreCancelled=true) public void drop(PlayerDropItemEvent e) { if (marked(e.getItemDrop().getItemStack())) e.setCancelled(true); }
}
