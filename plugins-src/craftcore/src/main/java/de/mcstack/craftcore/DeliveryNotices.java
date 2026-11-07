package de.mcstack.craftcore;
import java.sql.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
final class DeliveryNotices {
 private static final Map<UUID,Set<String>> seen=new HashMap<>();
 private static Set<String> openIds(CraftCore core,Player p)throws Exception {return core.tx(c->{Set<String> ids=new HashSet<>();try(PreparedStatement q=c.prepareStatement("SELECT id FROM pending_deliveries WHERE player_uuid=? AND status='offen'")){q.setString(1,p.getUniqueId().toString());try(ResultSet r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}}return ids;});}
 private static void notice(Player p){p.sendMessage(Component.text("Lieferung im Abholfach: ",NamedTextColor.GOLD).append(Component.text("[Abholen]",NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/abholen"))));}
 static void join(CraftCore core,Player p){try{Set<String> ids=openIds(core,p);seen.put(p.getUniqueId(),new HashSet<>(ids));if(!ids.isEmpty())notice(p);}catch(Exception ex){core.getLogger().warning("Abholfach-Hinweis: "+ex);}}
 static void quit(Player p){seen.remove(p.getUniqueId());}
 private static final java.util.concurrent.atomic.AtomicBoolean polling=new java.util.concurrent.atomic.AtomicBoolean();
 /** Main thread: snapshot online players; one batched read runs async; the seen map is only touched on the main thread. */
 static void poll(CraftCore core){
  List<String> online=new ArrayList<>();for(Player p:Bukkit.getOnlinePlayers())online.add(p.getUniqueId().toString());
  if(online.isEmpty()||!polling.compareAndSet(false,true))return;
  try{Bukkit.getScheduler().runTaskAsynchronously(core,()->{try{
   Map<UUID,Set<String>> open=core.tx(c->{Map<UUID,Set<String>> out=new HashMap<>();for(String id:online)out.put(UUID.fromString(id),new HashSet<>());
    try(PreparedStatement q=c.prepareStatement("SELECT player_uuid,id FROM pending_deliveries WHERE status='offen' AND player_uuid IN ("+String.join(",",Collections.nCopies(online.size(),"?"))+")")){for(int i=0;i<online.size();i++)q.setString(i+1,online.get(i));try(ResultSet r=q.executeQuery()){while(r.next()){Set<String> ids=out.get(UUID.fromString(r.getString(1)));if(ids!=null)ids.add(r.getString(2));}}}return out;});
   Bukkit.getScheduler().runTask(core,()->{for(var entry:open.entrySet()){Player p=Bukkit.getPlayer(entry.getKey());if(p==null)continue;Set<String> ids=entry.getValue();Set<String> known=seen.get(entry.getKey());if(known==null){seen.put(entry.getKey(),new HashSet<>(ids));if(!ids.isEmpty())notice(p);}else {if(ids.stream().anyMatch(id->!known.contains(id)))notice(p);known.addAll(ids);}}});
  }catch(Exception ex){core.getLogger().warning("Abholfach-Hinweis: "+ex);}finally{polling.set(false);}});}
  catch(RuntimeException ex){polling.set(false);throw ex;}
 }
 static void rememberOpen(CraftCore core,Player p)throws Exception {Set<String> known=seen.get(p.getUniqueId());if(known!=null)known.addAll(openIds(core,p));}

}
