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
 static void poll(CraftCore core){for(Player p:Bukkit.getOnlinePlayers())try{Set<String> ids=openIds(core,p);Set<String> known=seen.get(p.getUniqueId());if(known==null){seen.put(p.getUniqueId(),new HashSet<>(ids));if(!ids.isEmpty())notice(p);}else {if(ids.stream().anyMatch(id->!known.contains(id)))notice(p);known.addAll(ids);}}catch(Exception ex){core.getLogger().warning("Abholfach-Hinweis: "+ex);}}
 static void rememberOpen(CraftCore core,Player p)throws Exception {Set<String> known=seen.get(p.getUniqueId());if(known!=null)known.addAll(openIds(core,p));}

}
