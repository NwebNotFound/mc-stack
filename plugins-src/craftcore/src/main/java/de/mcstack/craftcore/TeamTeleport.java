package de.mcstack.craftcore;

import java.io.File;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;

final class TeamTeleport implements Listener, CommandExecutor {
  private static final long COMBAT=10_000, COOLDOWN=300_000, REQUEST=60_000;
  private final CraftCore plugin;
  private final boolean survival;
  private final Map<UUID,Long> damage=new HashMap<>(), cooldown=new HashMap<>();
  private final Map<UUID,Request> requests=new HashMap<>(), pending=new HashMap<>();
  private final File file;
  private record Request(UUID token, UUID from, UUID to, long expires) {}
  private static final class Holder implements InventoryHolder {
    Inventory inventory; final List<UUID> players; final int page; final UUID request;
    Holder(List<UUID> players,int page,UUID request){this.players=players;this.page=page;this.request=request;}
    public Inventory getInventory(){return inventory;}
  }
  TeamTeleport(CraftCore plugin,String server){this.plugin=plugin;survival=SpawnRoute.isSurvival(server);file=new File(plugin.getDataFolder(),"tpteam-cooldowns.yml");}
  void start(){
    YamlConfiguration saved=YamlConfiguration.loadConfiguration(file);
    for(String key:saved.getKeys(false)) try {long until=saved.getLong(key);if(until>System.currentTimeMillis())cooldown.put(UUID.fromString(key),until);} catch(IllegalArgumentException ignored) {}
    plugin.getCommand("tpteam").setExecutor(this); Bukkit.getPluginManager().registerEvents(this,plugin);
  }
  private void saveCooldowns(){
    YamlConfiguration saved=new YamlConfiguration(); long now=System.currentTimeMillis();
    cooldown.entrySet().removeIf(e->e.getValue()<=now);
    cooldown.forEach((id,until)->saved.set(id.toString(),until));
    try {saved.save(file);} catch(Exception e){plugin.getLogger().warning("Team-Teleport-Cooldown konnte nicht gespeichert werden: "+e.getMessage());}
  }
  /** A plain read: no craftcore-write lock, and only ever called off the server thread (see checkTeam). */
  private boolean sameTeam(UUID a,UUID b) throws Exception {
    try(java.sql.Connection c=plugin.connection()){return plugin.one(c,"SELECT a.team_id FROM members a JOIN members b ON a.team_id=b.team_id WHERE a.uuid=? AND b.uuid=?",a.toString(),b.toString())!=null;}
  }
  /**
   * The team check used to run a transaction inside the click handler and froze the tick when the database was slow
   * (#153). It now runs async; {@code then} gets the answer on the server thread, null when the database failed.
   */
  private void checkTeam(UUID a,UUID b,java.util.function.Consumer<Boolean> then){
    Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
      Boolean same;
      try{same=sameTeam(a,b);}catch(Exception ex){plugin.getLogger().warning("Team-Teleport: "+ex.getMessage());same=null;}
      Boolean result=same;
      Bukkit.getScheduler().runTask(plugin,()->then.accept(result));
    });
  }
  private boolean combat(UUID id){return TeamTeleportRules.inCombat(damage.getOrDefault(id,0L),System.currentTimeMillis());}
  private boolean ready(Player p){
    if(combat(p.getUniqueId())){p.sendMessage("[Craft] Im Kampf: 10 Sekunden nach Schaden warten.");return false;}
    long remaining=cooldown.getOrDefault(p.getUniqueId(),0L)-System.currentTimeMillis();
    if(TeamTeleportRules.active(cooldown.getOrDefault(p.getUniqueId(),0L),System.currentTimeMillis())){p.sendMessage("[Craft] Team-Teleport erst in "+((remaining+999)/1000)+" Sekunden wieder verfuegbar.");return false;}
    return true;
  }
  public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
    if(!(sender instanceof Player p)){sender.sendMessage("[Craft] Nur im Spiel verfuegbar.");return true;}
    if(!survival){p.sendMessage("[Craft] Team-Teleport ist nur auf Survival verfuegbar.");return true;}
    try {
      if(args.length==2 && args[0].equalsIgnoreCase("anfrage")){
        UUID token;try{token=UUID.fromString(args[1]);}catch(IllegalArgumentException e){p.sendMessage("[Craft] Ungueltige Anfrage.");return true;}
        Request r=requests.get(token);
        if(r==null || !TeamTeleportRules.recipient(r.to(),p.getUniqueId(),r.expires(),System.currentTimeMillis())){if(r!=null && r.expires()<=System.currentTimeMillis())requests.remove(token);p.sendMessage("[Craft] Anfrage abgelaufen oder nicht fuer dich.");return true;}
        openConfirmation(p,r);return true;
      }
      if(args.length!=0){p.sendMessage("[Craft] Nutze /tpteam.");return true;}
      List<UUID> ids=plugin.tx(c->{List<UUID> out=new ArrayList<>();try(var q=c.prepareStatement("SELECT b.uuid FROM members a JOIN members b ON a.team_id=b.team_id WHERE a.uuid=? AND b.uuid<>a.uuid")){q.setString(1,p.getUniqueId().toString());try(var rs=q.executeQuery()){while(rs.next()){UUID id=UUID.fromString(rs.getString(1));if(Bukkit.getPlayer(id)!=null)out.add(id);}}}return out;});
      ids.sort(Comparator.comparing(id->Bukkit.getPlayer(id).getName(),String.CASE_INSENSITIVE_ORDER));openList(p,ids,0);
    } catch(Exception ex){plugin.getLogger().warning("Team-Teleport: "+ex.getMessage());p.sendMessage("[Craft] Team konnte nicht geprueft werden.");}
    return true;
  }
  private ItemStack item(Material material,String title,String lore){ItemStack item=new ItemStack(material);ItemMeta m=item.getItemMeta();m.displayName(Component.text(title,NamedTextColor.AQUA));m.lore(List.of(Component.text(lore,NamedTextColor.GRAY)));item.setItemMeta(m);return item;}
  private void openList(Player p,List<UUID> ids,int page){
    Holder h=new Holder(ids,page,null);Inventory inv=Bukkit.createInventory(h,54,Component.text("Team-Teleport",NamedTextColor.GOLD));h.inventory=inv;
    for(int i=0;i<45 && page*45+i<ids.size();i++){Player member=Bukkit.getPlayer(ids.get(page*45+i));if(member==null)continue;ItemStack head=item(Material.PLAYER_HEAD,member.getName(),"Klicken: Anfrage senden");SkullMeta meta=(SkullMeta)head.getItemMeta();meta.setOwningPlayer(member);head.setItemMeta(meta);inv.setItem(i,head);}
    if(ids.isEmpty())inv.setItem(22,item(Material.BARRIER,"Keine Teammitglieder online","Teammitglieder muessen auf Survival sein"));
    if(page>0)inv.setItem(45,item(Material.ARROW,"Zurueck","Vorherige Seite"));if((page+1)*45<ids.size())inv.setItem(53,item(Material.ARROW,"Weiter","Naechste Seite"));p.openInventory(inv);
  }
  private void openConfirmation(Player p,Request r){Holder h=new Holder(List.of(),0,r.token());Inventory inv=Bukkit.createInventory(h,9,Component.text("Teleport-Anfrage",NamedTextColor.GOLD));h.inventory=inv;Player from=Bukkit.getPlayer(r.from());inv.setItem(3,item(Material.LIME_WOOL,"Annehmen",(from==null?"Spieler":from.getName())+" darf zu dir teleportieren"));inv.setItem(5,item(Material.RED_WOOL,"Ablehnen","Anfrage ablehnen"));p.openInventory(inv);}
  @EventHandler public void click(InventoryClickEvent e){
    if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;int slot=e.getRawSlot();if(slot<0 || slot>=e.getView().getTopInventory().getSize())return;
    try {
      if(h.request!=null){if(slot!=3 && slot!=5)return;Request r=requests.get(h.request);p.closeInventory();if(r==null || !TeamTeleportRules.recipient(r.to(),p.getUniqueId(),r.expires(),System.currentTimeMillis())){requests.remove(h.request);p.sendMessage("[Craft] Anfrage abgelaufen.");return;}requests.remove(h.request);if(slot==5){p.sendMessage("[Craft] Anfrage abgelehnt.");Player from=Bukkit.getPlayer(r.from());if(from!=null)from.sendMessage("[Craft] "+p.getName()+" hat deine Anfrage abgelehnt.");return;}accept(p,r);return;}
      if(slot==45 && h.page>0){openList(p,h.players,h.page-1);return;}if(slot==53 && (h.page+1)*45<h.players.size()){openList(p,h.players,h.page+1);return;}
      int index=h.page*45+slot;if(slot>=45 || index>=h.players.size())return;Player target=Bukkit.getPlayer(h.players.get(index));p.closeInventory();
      if(target==null){p.sendMessage("[Craft] Teammitglied nicht mehr verfuegbar.");return;}
      UUID targetId=target.getUniqueId();
      checkTeam(p.getUniqueId(),targetId,same->{
        Player member=Bukkit.getPlayer(targetId);if(!p.isOnline())return;
        if(same==null){p.sendMessage("[Craft] Aktion fehlgeschlagen.");return;}
        if(member==null || !same){p.sendMessage("[Craft] Teammitglied nicht mehr verfuegbar.");return;}
        request(p,member);
      });
    } catch(Exception ex){plugin.getLogger().warning("Team-Teleport: "+ex.getMessage());p.sendMessage("[Craft] Aktion fehlgeschlagen.");}
  }
  private void request(Player p,Player target){
    if(!ready(p))return;if(combat(target.getUniqueId())){p.sendMessage("[Craft] Das Teammitglied ist im Kampf.");return;}
    requests.values().removeIf(r->r.from().equals(p.getUniqueId()) || r.expires()<=System.currentTimeMillis());Request r=new Request(UUID.randomUUID(),p.getUniqueId(),target.getUniqueId(),System.currentTimeMillis()+REQUEST);requests.put(r.token(),r);
    p.sendMessage("[Craft] Anfrage an "+target.getName()+" gesendet (60 Sekunden gueltig).");target.sendMessage(Component.text("[Craft] "+p.getName()+" moechte zu dir teleportieren. ",NamedTextColor.GOLD).append(Component.text("[Anfrage oeffnen]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpteam anfrage "+r.token()))));
  }
  @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
  private void accept(Player target,Request r){
    if(Bukkit.getPlayer(r.from())==null){target.sendMessage("[Craft] Spieler oder Team nicht mehr verfuegbar.");return;}
    checkTeam(r.from(),r.to(),same->{
      if(!target.isOnline())return;
      if(same==null){target.sendMessage("[Craft] Aktion fehlgeschlagen.");return;}
      Player from=Bukkit.getPlayer(r.from());if(from==null || !same){target.sendMessage("[Craft] Spieler oder Team nicht mehr verfuegbar.");return;}if(!ready(from) || combat(r.to())){target.sendMessage("[Craft] Teleport wegen Kampf oder Cooldown nicht moeglich.");return;}
      cancel(r.from(),"Neue Teleport-Anfrage.");pending.put(r.from(),r);from.sendMessage("[Craft] Anfrage angenommen. 3 Sekunden stillstehen!");target.sendMessage("[Craft] Anfrage angenommen. Teleport in 3 Sekunden.");
      Bukkit.getScheduler().runTaskLater(plugin,()->finish(r),60L);
    });
  }
  private void finish(Request r){
    if(pending.get(r.from())!=r)return;
    if(Bukkit.getPlayer(r.from())==null || Bukkit.getPlayer(r.to())==null){pending.remove(r.from());Player from=Bukkit.getPlayer(r.from());if(from!=null)from.sendMessage("[Craft] Team-Teleport abgebrochen: Spieler offline.");return;}
    // Moving or taking damage while the team check runs still cancels: pending is checked again afterwards.
    checkTeam(r.from(),r.to(),same->{
      if(pending.get(r.from())!=r)return;pending.remove(r.from());Player from=Bukkit.getPlayer(r.from()),target=Bukkit.getPlayer(r.to());if(from==null || target==null){if(from!=null)from.sendMessage("[Craft] Team-Teleport abgebrochen: Spieler offline.");return;}
      if(same==null){from.sendMessage("[Craft] Team-Teleport fehlgeschlagen.");return;}
      try {if(!same || combat(r.from()) || combat(r.to()) || !ready(from)){from.sendMessage("[Craft] Team-Teleport nicht mehr moeglich.");return;}if(from.teleport(target.getLocation())){cooldown.put(r.from(),System.currentTimeMillis()+COOLDOWN);saveCooldowns();from.sendMessage("[Craft] Team-Teleport abgeschlossen.");target.sendMessage("[Craft] "+from.getName()+" ist zu dir teleportiert.");}else from.sendMessage("[Craft] Teleport fehlgeschlagen.");}catch(Exception ex){plugin.getLogger().warning("Team-Teleport: "+ex.getMessage());from.sendMessage("[Craft] Team-Teleport fehlgeschlagen.");}
    });
  }
  private void cancel(UUID from,String reason){Request r=pending.remove(from);if(r!=null){Player p=Bukkit.getPlayer(from);if(p!=null)p.sendMessage("[Craft] Team-Teleport abgebrochen: "+reason);}}
  @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void move(PlayerMoveEvent e){if(e.getTo()!=null && (e.getFrom().getWorld()!=e.getTo().getWorld() || e.getFrom().getX()!=e.getTo().getX() || e.getFrom().getY()!=e.getTo().getY() || e.getFrom().getZ()!=e.getTo().getZ()))cancel(e.getPlayer().getUniqueId(),"Bewegung.");}
  @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p && e.getFinalDamage()>0){UUID id=p.getUniqueId();damage.put(id,System.currentTimeMillis());for(Request r:new ArrayList<>(pending.values()))if(r.from().equals(id)||r.to().equals(id))cancel(r.from(),"Schaden.");}}
  @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();damage.remove(id);requests.values().removeIf(r->r.from().equals(id)||r.to().equals(id));for(Request r:new ArrayList<>(pending.values()))if(r.from().equals(id)||r.to().equals(id))cancel(r.from(),"Spieler offline.");}
}
