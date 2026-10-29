package de.mcstack.craftcore;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

final class Trade implements Listener {
  static final double DISTANCE=10.0;
  final CraftCore core;
  final Map<UUID,Session> active=new HashMap<>();
  final Map<UUID,Request> requests=new HashMap<>();
  record Request(UUID from,long expires) {}
  static final class Session implements InventoryHolder {
    final Player a,b; final TradeState state=new TradeState(); Inventory inv; boolean ended;
    Session(Player a,Player b){this.a=a;this.b=b;}
    public Inventory getInventory(){return inv;}
    boolean first(Player p){return p.getUniqueId().equals(a.getUniqueId());}
  }
  Trade(CraftCore core){this.core=core;}
  void start(){Bukkit.getPluginManager().registerEvents(this,core); Bukkit.getScheduler().runTaskTimer(core,this::tick,1,1);}
  boolean near(Player a,Player b){return a.isOnline() && b.isOnline() && !a.isDead() && !b.isDead() && a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation())<=DISTANCE*DISTANCE;}
  void command(Player p,String[] args){
    if(args.length!=1){p.sendMessage("/trade <Spieler> oder /trade accept");return;}
    if(active.containsKey(p.getUniqueId())){p.sendMessage("Du handelst bereits.");return;}
    if(args[0].equalsIgnoreCase("accept")){
      Request r=requests.remove(p.getUniqueId()); Player from=r==null?null:Bukkit.getPlayer(r.from());
      if(r==null || r.expires()<System.currentTimeMillis() || from==null || active.containsKey(from.getUniqueId()) || !near(p,from)){p.sendMessage("Keine gueltige Anfrage in Reichweite.");return;}
      if(!emptyCursor(p) || !emptyCursor(from)){p.sendMessage("Beide Spieler muessen den Cursor leeren.");from.sendMessage("Beide Spieler muessen den Cursor leeren.");return;}
      Session s=new Session(from,p); s.inv=Bukkit.createInventory(s,54,"Handel: "+from.getName()+" / "+p.getName());
      for(int i=0;i<54;i++) if(!offer(i)) s.inv.setItem(i,button(Material.GRAY_STAINED_GLASS_PANE," "));
      active.put(from.getUniqueId(),s);active.put(p.getUniqueId(),s); requests.remove(from.getUniqueId());
      refresh(s);from.openInventory(s.inv);p.openInventory(s.inv);return;
    }
    Player target=Bukkit.getPlayerExact(args[0]);
    if(target==null || target.equals(p) || !near(p,target) || active.containsKey(target.getUniqueId())){p.sendMessage("Spieler muss verfuegbar und innerhalb von 10 Bloecken sein.");return;}
    requests.put(target.getUniqueId(),new Request(p.getUniqueId(),System.currentTimeMillis()+30000));
    p.sendMessage("Handelsanfrage an "+target.getName()+" gesendet (30 Sekunden).");target.sendMessage(p.getName()+" moechte handeln. Annehmen mit /trade accept");
  }
  boolean emptyCursor(Player p){ItemStack c=p.getItemOnCursor();return c==null || c.getType().isAir();}
  static boolean offer(int slot){return slot>=0 && slot<18 || slot>=27 && slot<45;}
  ItemStack button(Material m,String name){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.setDisplayName(name);i.setItemMeta(meta);return i;}
  void refresh(Session s){s.inv.setItem(18,button(s.state.first?Material.LIME_WOOL:Material.RED_WOOL,s.a.getName()+": "+(s.state.first?"bestaetigt":"bestaetigen")));s.inv.setItem(26,button(s.state.second?Material.LIME_WOOL:Material.RED_WOOL,s.b.getName()+": "+(s.state.second?"bestaetigt":"bestaetigen")));s.inv.setItem(22,button(Material.CLOCK,s.state.ticks<0?"Beide bestaetigen": "Handel in "+((s.state.ticks+19)/20)+" s"));}
  @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void click(InventoryClickEvent e){
    if(!(e.getView().getTopInventory().getHolder() instanceof Session s))return; e.setCancelled(true);
    if(s.ended || !(e.getWhoClicked() instanceof Player p) || active.get(p.getUniqueId())!=s || !near(s.a,s.b))return;
    if(e.getClick()!=ClickType.LEFT || !emptyCursor(p))return;
    int raw=e.getRawSlot(); boolean first=s.first(p); int start=first?0:27;
    if(raw==(first?18:26)){s.state.confirm(first);refresh(s);return;}
    if(raw>=start && raw<start+18){ItemStack item=s.inv.getItem(raw);if(item==null || item.getType().isAir())return;s.inv.setItem(raw,null);give(p,item);s.state.changed();refresh(s);return;}
    if(e.getClickedInventory()!=p.getInventory() || raw<54 || e.getSlot()<0 || e.getSlot()>=36)return;
    ItemStack item=e.getCurrentItem();if(item==null || item.getType().isAir())return;
    for(int i=start;i<start+18;i++)if(s.inv.getItem(i)==null || s.inv.getItem(i).getType().isAir()){s.inv.setItem(i,item.clone());e.setCurrentItem(null);s.state.changed();refresh(s);return;}
    p.sendMessage("Deine Angebotsseite ist voll.");
  }
  @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Session)e.setCancelled(true);}
  @EventHandler public void close(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Session s && !s.ended)cancel(s,null,"Handel abgebrochen.");}
  @EventHandler public void quit(PlayerQuitEvent e){Session s=active.get(e.getPlayer().getUniqueId());if(s!=null)cancel(s,null,"Handel wegen Logout abgebrochen.");requests.remove(e.getPlayer().getUniqueId());requests.values().removeIf(r->r.from().equals(e.getPlayer().getUniqueId()));}
  @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e){Session s=active.get(e.getEntity().getUniqueId());if(s!=null)cancel(s,e,"Handel wegen Tod abgebrochen.");}
  @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void move(PlayerMoveEvent e){Session s=active.get(e.getPlayer().getUniqueId()); if(s==null || e.getTo()==null)return; Player other=s.first(e.getPlayer())?s.b:s.a; if(!e.getTo().getWorld().equals(other.getWorld()) || e.getTo().distanceSquared(other.getLocation())>DISTANCE*DISTANCE)cancel(s,null,"Handel wegen Distanz oder Weltwechsel abgebrochen.");}
  @EventHandler public void world(PlayerChangedWorldEvent e){Session s=active.get(e.getPlayer().getUniqueId());if(s!=null)cancel(s,null,"Handel wegen Weltwechsel abgebrochen.");}
  void give(Player p,ItemStack stack){for(ItemStack left:p.getInventory().addItem(stack).values()){try{core.tx(c->{Deliveries.insert(c,UUID.randomUUID().toString(),p.getUniqueId().toString(),left,"trade_return");return null;});}catch(Exception ex){core.getLogger().severe("Trade-Rueckgabe fehlgeschlagen: "+ex);p.getWorld().dropItemNaturally(p.getLocation(),left);}}p.saveData();}
  List<ItemStack> take(Session s,int start){List<ItemStack> items=new ArrayList<>();for(int i=start;i<start+18;i++){ItemStack item=s.inv.getItem(i);if(item!=null && !item.getType().isAir()){items.add(item.clone());s.inv.setItem(i,null);}}return items;}
  void cancel(Session s,PlayerDeathEvent death,String reason){
    if(s.ended)return;s.ended=true;active.remove(s.a.getUniqueId());active.remove(s.b.getUniqueId());
    for(Player p:List.of(s.a,s.b)){for(ItemStack i:take(s,s.first(p)?0:27)){if(death!=null && death.getEntity().equals(p) && !death.getKeepInventory())death.getDrops().add(i);else give(p,i);}p.sendMessage(reason);}
    Runnable close=()->{for(Player p:List.of(s.a,s.b))if(p.isOnline() && p.getOpenInventory().getTopInventory()==s.inv)p.closeInventory();}; if(core.isEnabled())Bukkit.getScheduler().runTask(core,close);else close.run();
  }
  boolean fits(Player p,List<ItemStack> incoming){ItemStack[] contents=p.getInventory().getStorageContents();ItemStack[] copy=new ItemStack[contents.length];for(int i=0;i<copy.length;i++)if(contents[i]!=null)copy[i]=contents[i].clone();for(ItemStack item:incoming){int left=item.getAmount();for(ItemStack slot:copy)if(slot!=null && slot.isSimilar(item)){int n=Math.min(left,Math.max(0,slot.getMaxStackSize()-slot.getAmount()));slot.setAmount(slot.getAmount()+n);left-=n;}for(int i=0;i<copy.length && left>0;i++)if(copy[i]==null || copy[i].getType().isAir()){int n=Math.min(left,item.getMaxStackSize());copy[i]=item.clone();copy[i].setAmount(n);left-=n;}if(left>0)return false;}return true;}
  List<ItemStack> snapshot(Session s,int start){List<ItemStack> items=new ArrayList<>();for(int i=start;i<start+18;i++){ItemStack item=s.inv.getItem(i);if(item!=null && !item.getType().isAir())items.add(item.clone());}return items;}
  void tick(){for(Session s:new HashSet<>(active.values())){if(!near(s.a,s.b) || s.a.getOpenInventory().getTopInventory()!=s.inv || s.b.getOpenInventory().getTopInventory()!=s.inv){cancel(s,null,"Handel wegen Distanz oder geschlossenem Inventar abgebrochen.");continue;}if(s.state.ticks>=0){if(s.state.tick()){List<ItemStack> a=snapshot(s,0),b=snapshot(s,27);if(!fits(s.a,b)||!fits(s.b,a)){s.state.changed();refresh(s);s.a.sendMessage("Nicht genug Inventarplatz; bitte erneut bestaetigen.");s.b.sendMessage("Nicht genug Inventarplatz; bitte erneut bestaetigen.");continue;}s.ended=true;active.remove(s.a.getUniqueId());active.remove(s.b.getUniqueId());take(s,0);take(s,27);for(ItemStack item:b)give(s.a,item);for(ItemStack item:a)give(s.b,item);s.a.closeInventory();s.b.closeInventory();s.a.sendMessage("Handel abgeschlossen.");s.b.sendMessage("Handel abgeschlossen.");}else if(s.state.ticks%20==0)refresh(s);}}}
  void shutdown(){for(Session s:new HashSet<>(active.values()))cancel(s,null,"Handel wegen Serverstopp abgebrochen.");}
}
