package de.mcstack.craftcore;

import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/** Persistent lobby NPCs. Trading through their preview stays disabled until delivery is safe. */
final class Npcs implements Listener {
  private static final Set<String> TYPES=Set.of("bank", "ah");
  private final CraftCore core;
  private final NamespacedKey key;
  Npcs(CraftCore core) { this.core=core; key=new NamespacedKey(core,"lobby_npc"); }
  void start() {
    Bukkit.getPluginManager().registerEvents(this,core);
    if(!isLobby()) return;
    for(String type:TYPES) {
      Location pos=core.getConfig().getLocation("npcs."+type+".location");
      if(pos==null || pos.getWorld()==null) continue;
      try { removeAt(pos,type); spawn(type,pos); }
      catch(Exception e) { core.getLogger().warning("NPC "+type+" konnte nicht geladen werden: "+e); }
    }
  }
  private boolean isLobby() { return "lobby".equalsIgnoreCase(System.getenv().getOrDefault("CRAFTCORE_SERVER", "")); }
  private String type(Entity entity) { return entity.getPersistentDataContainer().get(key,PersistentDataType.STRING); }
  private void removeAt(Location pos,String type) {
    for(Entity e:pos.getChunk().getEntities()) if(type.equals(type(e))) e.remove();
  }
  private void spawn(String type,Location pos) {
    Villager v=pos.getWorld().spawn(pos,Villager.class,entity->{
      entity.getPersistentDataContainer().set(key,PersistentDataType.STRING,type);
      entity.customName(Component.text(type.equals("bank")?"Bankier":"Auktionator"));
      entity.setCustomNameVisible(true); entity.setAI(false); entity.setInvulnerable(true);
      entity.setSilent(true); entity.setCollidable(false); entity.setGravity(false);
      entity.setPersistent(true); entity.setRemoveWhenFarAway(false);
    });
    if(!v.isValid()) throw new IllegalStateException("Spawn wurde verhindert");
  }
  void command(Player p,String[] args) {
    if(!p.hasPermission("craftcore.npc.set")) {p.sendMessage("Keine Berechtigung.");return;}
    if(!isLobby()) {p.sendMessage("Bank- und Auktions-NPCs können nur in der Lobby gesetzt werden.");return;}
    if(args.length!=2 || !args[0].equalsIgnoreCase("set") || !TYPES.contains(args[1].toLowerCase(Locale.ROOT))) {p.sendMessage("/npc set bank|ah");return;}
    String type=args[1].toLowerCase(Locale.ROOT);
    Location old=core.getConfig().getLocation("npcs."+type+".location");
    Location pos=p.getLocation().clone();
    try {
      if(old!=null && old.getWorld()!=null) removeAt(old,type);
      removeAt(pos,type); spawn(type,pos);
      core.getConfig().set("npcs."+type+".location",pos); core.saveConfig();
      p.sendMessage("NPC "+type+" gesetzt. Die GUI ist bis zur sicheren Item-Auslieferung nur eine Vorschau.");
    } catch(Exception e) {core.getLogger().warning("NPC setzen: "+e);p.sendMessage("NPC konnte nicht gesetzt werden; bitte Serverlog prüfen.");}
  }
  @EventHandler public void damage(EntityDamageEvent e) {if(type(e.getEntity())!=null && TYPES.contains(type(e.getEntity()))) e.setCancelled(true);}
  @EventHandler public void interact(PlayerInteractEntityEvent e) {
    String type=type(e.getRightClicked()); if(type==null || !TYPES.contains(type)) return;
    e.setCancelled(true); if(e.getHand()!=EquipmentSlot.HAND || !isLobby()) return;
    Player p=e.getPlayer();
    try {
      if(type.equals("bank")) Market.instance.command(p,"bank",new String[0]);
      else Auction.instance.command(p,new String[0]);
      core.npcPreviews.add(p.getOpenInventory().getTopInventory());
      p.sendMessage("Nur Vorschau: Handel und Item-Auslieferung hier gesperrt, bis sie absturzsicher sind.");
    } catch(Exception ex) {core.getLogger().warning("NPC-GUI: "+ex);p.sendMessage("Ansicht konnte nicht geöffnet werden.");}
  }
}
