package de.mcstack.craftcore;
import java.sql.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
final class InviteRewards {
 static void start(CraftCore core,boolean survival){
  Bukkit.getScheduler().runTaskTimer(core,()->notifyOnline(core),20L,20L);
  if(!survival)return;
  double hours=core.getConfig().getDouble("invites.reward-hours",2);long cp=Money.cents(core.getConfig().getDouble("invites.reward-cp",500));if(!Double.isFinite(hours)||hours<0||hours>Long.MAX_VALUE/3600.0||cp<=0)throw new IllegalArgumentException("Ungueltige invites-Konfiguration");long seconds=(long)Math.ceil(hours*3600);
  Bukkit.getScheduler().runTaskTimerAsynchronously(core,()->poll(core,seconds,cp),1200L,1200L);
 }
 static void poll(CraftCore core,long seconds,long cp){try{List<Long> ids=core.tx(c->InviteStore.eligible(c,seconds));for(long id:ids)core.tx(c->InviteStore.reward(c,id,seconds,cp));}catch(Exception e){core.getLogger().warning("Einladungsbelohnung: "+e.getMessage());}}
 record Notice(long id,String inviter,long cp){}
 private static final java.util.concurrent.atomic.AtomicBoolean notifying=new java.util.concurrent.atomic.AtomicBoolean();
 /** Main thread: snapshot online players; one batched DB round trip runs async; messages are sent back on the main thread. */
 static void notifyOnline(CraftCore core){
  List<String> online=new ArrayList<>();for(Player p:Bukkit.getOnlinePlayers())online.add(p.getUniqueId().toString());
  if(online.isEmpty()||!notifying.compareAndSet(false,true))return;
  try{Bukkit.getScheduler().runTaskAsynchronously(core,()->{try{
   List<Notice> notices=core.tx(c->{List<Notice> out=new ArrayList<>();
    try(PreparedStatement q=c.prepareStatement("SELECT invite_id,inviter_uuid,cp FROM invite_reward_notices WHERE reported=FALSE AND inviter_uuid IN ("+String.join(",",Collections.nCopies(online.size(),"?"))+") FOR UPDATE")){for(int i=0;i<online.size();i++)q.setString(i+1,online.get(i));try(ResultSet r=q.executeQuery()){while(r.next())out.add(new Notice(r.getLong(1),r.getString(2),r.getLong(3)));}}
    for(Notice n:out)core.exec(c,"UPDATE invite_reward_notices SET reported=TRUE WHERE invite_id=?",n.id());return out;});
   if(!notices.isEmpty())Bukkit.getScheduler().runTask(core,()->{for(Notice n:notices){Player p=Bukkit.getPlayer(UUID.fromString(n.inviter()));if(p!=null)p.sendMessage("[Craft] Deine Einladung hat dir "+Money.format(n.cp())+" eingebracht!");}});
  }catch(Exception e){core.getLogger().warning("Einladungsmeldung: "+e.getMessage());}finally{notifying.set(false);}});}
  catch(RuntimeException e){notifying.set(false);throw e;}
 }
}
