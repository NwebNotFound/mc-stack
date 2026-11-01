package de.mcstack.craftcore;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

final class Motivation implements Listener,CommandExecutor {
 private final CraftCore plugin;
 private final Map<UUID,Long> clock=new HashMap<>();
 private final Map<UUID,String> prefixes=new ConcurrentHashMap<>();
 private static final long[] HOURS={5,25,100,300}, COUNTS={100,1000,10000,100000};
 private static final class Holder implements InventoryHolder {Inventory inventory; public Inventory getInventory(){return inventory;}}
 Motivation(CraftCore p){plugin=p;}
 void start() throws Exception {
  plugin.tx(c->{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS motivation (uuid CHAR(36) PRIMARY KEY, login_day DATE NULL, streak INT NOT NULL DEFAULT 0, seconds_played BIGINT NOT NULL DEFAULT 0, blocks BIGINT NOT NULL DEFAULT 0, kills BIGINT NOT NULL DEFAULT 0, badge7 BOOLEAN NOT NULL DEFAULT FALSE, badge30 BOOLEAN NOT NULL DEFAULT FALSE, badge INT NOT NULL DEFAULT 0)");}return null;});
  plugin.getCommand("stats").setExecutor(this);Bukkit.getPluginManager().registerEvents(this,plugin);
  Bukkit.getScheduler().runTaskTimer(plugin,()->Bukkit.getOnlinePlayers().forEach(this::flush),1200,1200);
  for(Player p:Bukkit.getOnlinePlayers())joinPlayer(p);
 }
 void shutdown(){for(Player p:Bukkit.getOnlinePlayers())flush(p);}
 private String prefix(MotivationStore.State s){String rank=MotivationStore.rank(s.seconds());String badge=s.badge()==7&&s.badge7()?"[Treue] ":s.badge()==30&&s.badge30()?"[Stammgast] ":"";return (rank.isEmpty()?"":"["+rank+"] ")+badge;}
 private void joinPlayer(Player p){clock.put(p.getUniqueId(),System.nanoTime());try{int n=plugin.tx(c->MotivationStore.login(c,p.getUniqueId().toString(),ChallengePeriods.day(Instant.now(),ZoneId.of("Europe/Berlin"),5)));MotivationStore.State s=plugin.tx(c->MotivationStore.lock(c,p.getUniqueId().toString()));prefixes.put(p.getUniqueId(),prefix(s));if(n>0){p.sendMessage(Component.text("[Craft] Tageslogin: 10 CP | Serie: "+n+" Tage",NamedTextColor.GOLD));if(n==7||n==30)p.sendMessage(Component.text("[Craft] Neues Chat-Abzeichen! Wähle es in /stats.",NamedTextColor.AQUA));}}catch(Exception ex){plugin.getLogger().warning("Tageslogin: "+ex);p.sendMessage("[Craft] Tageslogin nicht verfügbar; /stats erneut öffnen.");}}
 @EventHandler public void join(PlayerJoinEvent e){joinPlayer(e.getPlayer());}
 @EventHandler public void quit(PlayerQuitEvent e){flush(e.getPlayer());clock.remove(e.getPlayer().getUniqueId());prefixes.remove(e.getPlayer().getUniqueId());}
 private void flush(Player p){UUID id=p.getUniqueId();Long start=clock.get(id);if(start==null)return;long now=System.nanoTime(),seconds=(now-start)/1_000_000_000L;if(seconds<=0)return;try{MotivationStore.State old=plugin.tx(c->{var s=MotivationStore.lock(c,id.toString());plugin.exec(c,"UPDATE motivation SET seconds_played=seconds_played+? WHERE uuid=?",seconds,id.toString());return s;});clock.put(id,start+seconds*1_000_000_000L);long before=old.seconds()/3600,after=(old.seconds()+seconds)/3600;long milestone=MotivationStore.crossed(before,after,HOURS);if(milestone>0)announce(p,"Spielzeit",milestone,"Stunden");prefixes.put(id,(MotivationStore.rank(old.seconds()+seconds).isEmpty()?"":"["+MotivationStore.rank(old.seconds()+seconds)+"] ")+(old.badge()==7&&old.badge7()?"[Treue] ":old.badge()==30&&old.badge30()?"[Stammgast] ":""));}catch(Exception ex){plugin.getLogger().warning("Spielzeit: "+ex);}}
 private void increment(Player p,String column,String label){try{long milestone=plugin.tx(c->{var s=MotivationStore.lock(c,p.getUniqueId().toString());long before=column.equals("blocks")?s.blocks():s.kills();plugin.exec(c,"UPDATE motivation SET "+column+"="+column+"+1 WHERE uuid=?",p.getUniqueId().toString());return MotivationStore.crossed(before,before+1,COUNTS);});if(milestone>0)announce(p,label,milestone,"");}catch(Exception ex){plugin.getLogger().warning("Statistik: "+ex);}}
 private void announce(Player p,String label,long value,String unit){Bukkit.broadcast(Component.text("[Craft] "+p.getName()+" erreicht "+value+" "+unit+" "+label+"!",NamedTextColor.GOLD));}
 @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void block(BlockBreakEvent e){if(e.getPlayer().getGameMode()==GameMode.SURVIVAL)increment(e.getPlayer(),"blocks","abgebaute Blöcke");}
 @EventHandler(priority=EventPriority.MONITOR) public void kill(EntityDeathEvent e){Player p=e.getEntity().getKiller();if(p!=null&&p.getGameMode()==GameMode.SURVIVAL&&!(e.getEntity() instanceof Player))increment(p,"kills","besiegte Kreaturen");}
 @EventHandler public void chat(AsyncChatEvent e){String prefix=prefixes.getOrDefault(e.getPlayer().getUniqueId(),"");if(prefix.isEmpty())return;var previous=e.renderer();e.renderer((source,name,message,viewer)->Component.text(prefix,NamedTextColor.GOLD).append(previous.render(source,name,message,viewer)));}
 public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(sender instanceof Player p)open(p);else sender.sendMessage("Nur im Spiel.");return true;}
 private void item(Inventory inv,int slot,Material material,String name,String... lines){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(Component.text(name,NamedTextColor.GOLD));m.lore(Arrays.stream(lines).map(x->Component.text(x,NamedTextColor.GRAY)).toList());i.setItemMeta(m);inv.setItem(slot,i);}
 private void open(Player p){try{int n=plugin.tx(c->MotivationStore.login(c,p.getUniqueId().toString(),ChallengePeriods.day(Instant.now(),ZoneId.of("Europe/Berlin"),5)));if(n>0)p.sendMessage("[Craft] Tageslogin: 10 CP | Serie: "+n+" Tage");flush(p);var s=plugin.tx(c->MotivationStore.lock(c,p.getUniqueId().toString()));prefixes.put(p.getUniqueId(),prefix(s));Holder h=new Holder();Inventory inv=Bukkit.createInventory(h,27,Component.text("Deine Statistik",NamedTextColor.GOLD));h.inventory=inv;item(inv,0,Material.CLOCK,"Spielzeit: "+(s.seconds()/3600)+" Stunden","Rang: "+(MotivationStore.rank(s.seconds()).isEmpty()?"Noch keiner":MotivationStore.rank(s.seconds())),"Ränge: 5 / 25 / 100 / 300 Stunden");item(inv,2,Material.GRASS_BLOCK,"Abgebaute Blöcke: "+s.blocks(),"Meilensteine: 100 / 1.000 / 10.000 / 100.000");item(inv,4,Material.IRON_SWORD,"Besiegte Kreaturen: "+s.kills(),"Meilensteine: 100 / 1.000 / 10.000 / 100.000");item(inv,6,Material.GOLD_INGOT,"Login-Serie: "+s.streak()+" Tage","Täglich 10 CP; Tageswechsel 05:00 Berlin","Tag 7 und 30: Chat-Abzeichen");item(inv,18,Material.BARRIER,"Kein Abzeichen",s.badge()==0?"Ausgewählt":"Klicken zum Auswählen");item(inv,20,Material.SUNFLOWER,"Treue (Tag 7)",s.badge7()?(s.badge()==7?"Ausgewählt":"Klicken zum Auswählen"):"Noch gesperrt");item(inv,22,Material.NETHER_STAR,"Stammgast (Tag 30)",s.badge30()?(s.badge()==30?"Ausgewählt":"Klicken zum Auswählen"):"Noch gesperrt");p.openInventory(inv);}catch(Exception ex){plugin.getLogger().warning("Stats: "+ex);p.sendMessage("[Craft] Statistik nicht verfügbar.");}}
 @EventHandler public void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof Holder))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;int badge=switch(e.getRawSlot()){case 18->0;case 20->7;case 22->30;default->-1;};if(badge<0)return;try{boolean ok=plugin.tx(c->{var s=MotivationStore.lock(c,p.getUniqueId().toString());if(badge==7&&!s.badge7()||badge==30&&!s.badge30())return false;plugin.exec(c,"UPDATE motivation SET badge=? WHERE uuid=?",badge,p.getUniqueId().toString());return true;});if(!ok)p.sendMessage("[Craft] Dieses Abzeichen ist noch gesperrt.");open(p);}catch(Exception ex){plugin.getLogger().warning("Abzeichen: "+ex);p.sendMessage("[Craft] Auswahl fehlgeschlagen.");}}
 @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
}
