package de.mcstack.craftcore;
import java.sql.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
final class InviteRewards {
 static void start(CraftCore core,boolean survival){
  Bukkit.getScheduler().runTaskTimer(core,()->{for(Player p:Bukkit.getOnlinePlayers())notify(core,p);},20L,20L);
  if(!survival)return;
  double hours=core.getConfig().getDouble("invites.reward-hours",2);long cp=Money.cents(core.getConfig().getDouble("invites.reward-cp",500));if(!Double.isFinite(hours)||hours<0||hours>Long.MAX_VALUE/3600.0||cp<=0)throw new IllegalArgumentException("Ungueltige invites-Konfiguration");long seconds=(long)Math.ceil(hours*3600);
  Bukkit.getScheduler().runTaskTimerAsynchronously(core,()->poll(core,seconds,cp),1200L,1200L);
 }
 static void poll(CraftCore core,long seconds,long cp){try{List<Long> ids=core.tx(c->InviteStore.eligible(c,seconds));for(long id:ids)core.tx(c->InviteStore.reward(c,id,seconds,cp));}catch(Exception e){core.getLogger().warning("Einladungsbelohnung: "+e.getMessage());}}
 static void notify(CraftCore core,Player p){try{core.tx(c->{try(PreparedStatement q=c.prepareStatement("SELECT invite_id,cp FROM invite_reward_notices WHERE inviter_uuid=? AND reported=FALSE FOR UPDATE")){q.setString(1,p.getUniqueId().toString());try(ResultSet r=q.executeQuery()){while(r.next()){p.sendMessage("[Craft] Deine Einladung hat dir "+Money.format(r.getLong(2))+" eingebracht!");core.exec(c,"UPDATE invite_reward_notices SET reported=TRUE WHERE invite_id=?",r.getLong(1));}}}return null;});}catch(Exception e){core.getLogger().warning("Einladungsmeldung: "+e.getMessage());}}
}
