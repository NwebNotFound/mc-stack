package de.mcstack.craftcore;

import java.sql.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import io.papermc.paper.event.player.PlayerTradeEvent;

final class Challenges implements Listener, CommandExecutor {
  private final CraftCore core; private final boolean survival;
  private final Map<UUID,Map<String,Double>> walking=new HashMap<>();
  private final Map<UUID,BossBar> bars=new HashMap<>();
  private final Map<UUID,org.bukkit.scheduler.BukkitTask> barTasks=new HashMap<>();
  private ZoneId zone; private int hour;
  /**
   * Challenge progress runs here, one write at a time: off the server thread (a slow database or a held craftcore-write
   * lock stalled the tick, #153) and still in order, so two updates of the same row never race.
   */
  private final java.util.concurrent.ExecutorService db=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"craftcore-challenges");t.setDaemon(true);return t;});
  record Def(String id,String type,String title,int goal,int cp,int points) {}
  record Row(String period,String id,String type,String title,int goal,int progress,int cp,int points,boolean complete) {}
  static final Set<String> TYPES=Set.of("KILL_SHEEP","KILL_ZOMBIE","WALK_NETHER","WALK_NORMAL","MINE_IRON","FISH","TRADE");
  static final class Holder implements InventoryHolder { Inventory inventory; public Inventory getInventory(){return inventory;} }
  Challenges(CraftCore core,String server){this.core=core;survival=SpawnRoute.isSurvival(server);}
  void start() throws Exception {
    zone=ZoneId.of(core.getConfig().getString("challenges.zone","Europe/Berlin")); hour=core.getConfig().getInt("challenges.reset-hour",5); if(hour<0||hour>23)throw new IllegalArgumentException("Ungueltige Challenge-Resetstunde");
    pool(false);pool(true);
    core.tx(c->{try(Statement s=c.createStatement()){
      s.execute("CREATE TABLE IF NOT EXISTS player_challenges (uuid CHAR(36) NOT NULL, period_key VARCHAR(12) NOT NULL, challenge_id VARCHAR(64) NOT NULL, type VARCHAR(32) NOT NULL, title VARCHAR(120) NOT NULL, goal INT NOT NULL, progress INT NOT NULL DEFAULT 0, cp INT NOT NULL, points INT NOT NULL, completed BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(uuid,period_key,challenge_id)) ENGINE=InnoDB");
      s.execute("CREATE TABLE IF NOT EXISTS player_season_points (uuid CHAR(36) PRIMARY KEY, points BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB");}return null;});
    core.getCommand("challenges").setExecutor(this); Bukkit.getPluginManager().registerEvents(this,core);
    Bukkit.getScheduler().runTaskTimer(core,()->{for(Player p:Bukkit.getOnlinePlayers())flush(p);},20L,20L);
  }
  List<Def> pool(boolean weekly){
    ConfigurationSection section=core.getConfig().getConfigurationSection("challenges."+(weekly?"weekly":"daily")); if(section==null)throw new IllegalArgumentException("Challenge-Pool fehlt");
    List<Def> list=new ArrayList<>(); for(String id:section.getKeys(false)) {ConfigurationSection d=section.getConfigurationSection(id); if(d==null)throw new IllegalArgumentException("Challenge ungueltig: "+id); String type=d.getString("type",""); String title=d.getString("title",""); int goal=d.getInt("goal"),cp=d.getInt("cp"),points=d.getInt("points"); if(id.length()>64||!TYPES.contains(type)||title.isBlank()||title.length()>120||goal<=0||points<=0||cp<(weekly?150:20)||cp>(weekly?300:50))throw new IllegalArgumentException("Challenge ungueltig: "+id);list.add(new Def(id,type,title,goal,cp,points));}
    if(list.size()<3)throw new IllegalArgumentException("Challenge-Pool braucht mindestens drei Aufgaben"); list.sort(Comparator.comparing(Def::id)); return list;
  }
  void ensure(Connection c,UUID id,LocalDate date,boolean weekly) throws Exception {
    String key=ChallengePeriods.period(date,weekly); String existing=core.one(c,"SELECT COUNT(*) FROM player_challenges WHERE uuid=? AND period_key=?",id.toString(),key); if(Integer.parseInt(existing)>0)return;
    List<Def> defs=pool(weekly); Collections.shuffle(defs,new Random(Objects.hash(id,key))); for(Def d:defs.subList(0,3))core.exec(c,"INSERT INTO player_challenges(uuid,period_key,challenge_id,type,title,goal,cp,points) VALUES(?,?,?,?,?,?,?,?)",id.toString(),key,d.id(),d.type(),d.title(),d.goal(),d.cp(),d.points());
  }
  List<Row> rows(Connection c,UUID id,LocalDate date) throws Exception {
    ensure(c,id,date,false);ensure(c,id,date,true);List<Row> out=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT period_key,challenge_id,type,title,goal,progress,cp,points,completed FROM player_challenges WHERE uuid=? AND period_key IN (?,?) ORDER BY period_key,challenge_id")){q.setString(1,id.toString());q.setString(2,ChallengePeriods.period(date,false));q.setString(3,ChallengePeriods.period(date,true));try(ResultSet r=q.executeQuery()){while(r.next())out.add(new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7),r.getInt(8),r.getBoolean(9)));}}return out;
  }
  void progress(Player p,String type,int amount){
    if(!survival||amount<=0||p.getGameMode()!=GameMode.SURVIVAL)return;
    UUID id=p.getUniqueId(); LocalDate day=ChallengePeriods.day(Instant.now(),zone,hour);
    try { db.execute(()->advance(id,day,type,amount)); }
    catch(java.util.concurrent.RejectedExecutionException e){core.getLogger().warning("Challenge-Fortschritt: "+e.getMessage());}
  }
  /** Database part of {@link #progress}, on the challenge thread; the bar and the chat line go back to the server thread. */
  private void advance(UUID id,LocalDate day,String type,int amount){
    List<Row> changed;
    try{changed=core.tx(c->{List<Row> result=new ArrayList<>(); for(Row r:rows(c,id,day))if(!r.complete()&&r.type().equals(type)){int n=(int)Math.min((long)r.goal(),(long)r.progress()+amount);boolean done=n>=r.goal();ChallengeStore.advance(c,id.toString(),r.period(),r.id(),n,done,r.cp(),r.points());result.add(new Row(r.period(),r.id(),r.type(),r.title(),r.goal(),n,r.cp(),r.points(),done));}return result;});}
    catch(Exception e){core.getLogger().warning("Challenge-Fortschritt: "+e.getMessage());return;}
    if(changed.isEmpty())return;
    try{Bukkit.getScheduler().runTask(core,()->{Player p=Bukkit.getPlayer(id);if(p==null)return;for(Row r:changed){showBar(p,r);if(r.complete())p.sendMessage("[Craft] Aufgabe abgeschlossen: "+r.title()+" (+"+r.cp()+" CP, +"+r.points()+" Season-Punkte)");}});}
    catch(RuntimeException disabled){/* plugin is disabling: the progress is saved, only the display is skipped */}
  }
  void showBar(Player p,Row r){UUID id=p.getUniqueId();BossBar bar=bars.computeIfAbsent(id,x->Bukkit.createBossBar("",BarColor.GREEN,BarStyle.SOLID));bar.setTitle(r.title()+" - "+r.progress()+" / "+r.goal());bar.setProgress(Math.min(1.0,(double)r.progress()/r.goal()));bar.addPlayer(p);org.bukkit.scheduler.BukkitTask old=barTasks.remove(id);if(old!=null)old.cancel();barTasks.put(id,Bukkit.getScheduler().runTaskLater(core,()->{bar.removeAll();bars.remove(id);barTasks.remove(id);},100L));}
  public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){if(!(sender instanceof Player p)){sender.sendMessage("[Craft] Nur im Spiel verfuegbar.");return true;}try{List<Row> rows=core.tx(c->rows(c,p.getUniqueId(),ChallengePeriods.day(Instant.now(),zone,hour)));long points=core.tx(c->{String v=core.one(c,"SELECT points FROM player_season_points WHERE uuid=?",p.getUniqueId().toString());return v==null?0L:Long.parseLong(v);});Holder h=new Holder();Inventory inv=Bukkit.createInventory(h,27,Component.text("Challenges",NamedTextColor.GOLD));h.inventory=inv;int daily=0,weekly=0;for(Row r:rows){int slot=r.period().startsWith("D:")?10+daily++:19+weekly++;ItemStack item=new ItemStack(r.complete()?Material.LIME_DYE:Material.PAPER);ItemMeta m=item.getItemMeta();m.displayName(Component.text((r.period().startsWith("D:")?"Taeglich: ":"Woechentlich: ")+r.title(),NamedTextColor.YELLOW));m.lore(List.of(Component.text("Fortschritt: "+r.progress()+" / "+r.goal(),NamedTextColor.GRAY),Component.text("Belohnung: "+r.cp()+" CP + "+r.points()+" Season-Punkte",NamedTextColor.GREEN),Component.text(r.complete()?"Abgeschlossen":"Offen",NamedTextColor.AQUA)));item.setItemMeta(m);inv.setItem(slot,item);}ItemStack score=new ItemStack(Material.NETHER_STAR);ItemMeta meta=score.getItemMeta();meta.displayName(Component.text("Season-Punkte: "+points,NamedTextColor.GOLD));meta.lore(List.of(Component.text("Reset: "+String.format("%02d:00",hour)+", woechentlich montags ("+zone+")",NamedTextColor.GRAY)));score.setItemMeta(meta);inv.setItem(4,score);p.openInventory(inv);}catch(Exception e){core.getLogger().warning("Challenges GUI: "+e.getMessage());p.sendMessage("[Craft] Challenges derzeit nicht verfuegbar.");}return true;}
  @EventHandler public void click(InventoryClickEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
  @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void kill(EntityDeathEvent e){Player p=e.getEntity().getKiller();if(p!=null){if(e.getEntityType()==EntityType.SHEEP)progress(p,"KILL_SHEEP",1);if(e.getEntityType()==EntityType.ZOMBIE)progress(p,"KILL_ZOMBIE",1);}}
  /** Silk Touch keeps the ore, so placing and breaking it again would count forever; only a break that yields raw iron counts. */
  static boolean minedIron(Material block,boolean silkTouch){return (block==Material.IRON_ORE||block==Material.DEEPSLATE_IRON_ORE)&&!silkTouch;}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void mine(BlockBreakEvent e){if(minedIron(e.getBlock().getType(),e.getPlayer().getInventory().getItemInMainHand().containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH)))progress(e.getPlayer(),"MINE_IRON",1);}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void fish(PlayerFishEvent e){if(e.getState()==PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item item && Set.of(Material.COD,Material.SALMON,Material.TROPICAL_FISH,Material.PUFFERFISH).contains(item.getItemStack().getType()))progress(e.getPlayer(),"FISH",1);}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void trade(PlayerTradeEvent e){if(e.getMerchant() instanceof Villager)progress(e.getPlayer(),"TRADE",1);}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){Player p=e.getPlayer();if(!survival||p.getGameMode()!=GameMode.SURVIVAL||p.isFlying()||p.isInsideVehicle()||e instanceof PlayerTeleportEvent||e.getTo()==null||e.getFrom().getWorld()!=e.getTo().getWorld())return;World.Environment env=p.getWorld().getEnvironment();String type=env==World.Environment.NETHER?"WALK_NETHER":env==World.Environment.NORMAL?"WALK_NORMAL":null;if(type==null)return;double dx=e.getTo().getX()-e.getFrom().getX(),dz=e.getTo().getZ()-e.getFrom().getZ(),dist=Math.hypot(dx,dz);if(dist>0&&dist<=1.5)walking.computeIfAbsent(p.getUniqueId(),x->new HashMap<>()).merge(type,dist,Double::sum);}
  void flush(Player p){Map<String,Double> map=walking.get(p.getUniqueId());if(map==null)return;for(String type:new ArrayList<>(map.keySet())){double d=map.get(type);int n=(int)d;if(n>0){map.put(type,d-n);progress(p,type,n);}}}
  @EventHandler public void quit(PlayerQuitEvent e){flush(e.getPlayer());walking.remove(e.getPlayer().getUniqueId());BossBar bar=bars.remove(e.getPlayer().getUniqueId());if(bar!=null)bar.removeAll();org.bukkit.scheduler.BukkitTask task=barTasks.remove(e.getPlayer().getUniqueId());if(task!=null)task.cancel();}
}
