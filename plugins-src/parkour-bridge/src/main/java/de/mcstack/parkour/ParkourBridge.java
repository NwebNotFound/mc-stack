package de.mcstack.parkour;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import net.crumb.lobbyParkour.database.*;
import net.crumb.lobbyParkour.systems.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class ParkourBridge extends JavaPlugin implements Listener {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<UUID, Candidate> candidates=new HashMap<>();
 private String password;
 private record Candidate(ParkourSession session, long ms) {}
 private record Entry(String name,long ms) {}

 private Connection db() throws SQLException { return DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000","craftcore",password); }
 @Override public void onEnable() {
  password=System.getenv("CRAFTCORE_DB_PASSWORD");
  if(password==null || password.isBlank()) { getLogger().severe("Datenbankpasswort fehlt"); getServer().getPluginManager().disablePlugin(this); return; }
  try { Class.forName("org.mariadb.jdbc.Driver"); try(Connection c=db(); Statement s=c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS parkour_times (uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, best_ms BIGINT NOT NULL, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))"); } }
  catch(Exception e) { getLogger().log(Level.SEVERE,"Parkour-Datenbank nicht verfuegbar",e); getServer().getPluginManager().disablePlugin(this); return; }
  saveDefaultConfig(); getServer().getPluginManager().registerEvents(this,this); refresh();
 }
 @Override public void onDisable() { worker.shutdown(); try { if(!worker.awaitTermination(10,TimeUnit.SECONDS)) getLogger().warning("Ausstehende Parkour-Datenbankaufgaben nicht abgeschlossen"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
 private Query query() { return new Query(ParkoursDatabase.getConnection()); }
 private Location checkpoint(ParkourSession s) throws SQLException {
  Query q=query(); Integer id=q.getParkourIdFromName(s.getParkourName()); if(id==null) return null;
  Location l=s.getLastReachedCheckpointIndex()>0 ? q.getCheckpointLocation(id,s.getLastReachedCheckpointIndex()) : q.getStartLocation(s.getParkourName());
  if(l==null || l.getWorld()==null) return null;
  return l.clone().add(0.5,0.1,0.5);
 }
 private void back(Player p,ParkourSession s) {
  try { Location l=checkpoint(s); if(l==null) { p.sendMessage("Parkour: Checkpoint nicht gefunden."); return; } l.setYaw(s.getLastCheckpointYaw()); l.setPitch(s.getLastCheckpointPitch()); p.setFallDistance(0); p.setVelocity(new Vector()); p.teleport(l); }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Checkpoint konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void move(PlayerMoveEvent e) {
  if(e.getTo()==null || e.getFrom().getY()==e.getTo().getY()) return;
  ParkourSession s=ParkourSessionManager.getSession(e.getPlayer().getUniqueId()); if(s==null) return;
  try { Location cp=checkpoint(s); if(cp!=null && cp.getWorld().equals(e.getTo().getWorld()) && e.getTo().getY()<cp.getY()-Math.max(1,getConfig().getDouble("fall-distance-below-checkpoint",4))) { e.setTo(cp); e.getPlayer().setFallDistance(0); e.getPlayer().setVelocity(new Vector()); } }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Checkpoint konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void damage(EntityDamageEvent e) {
  if(!(e.getEntity() instanceof Player p) || (e.getCause()!=EntityDamageEvent.DamageCause.VOID && e.getCause()!=EntityDamageEvent.DamageCause.FALL)) return;
  ParkourSession s=ParkourSessionManager.getSession(p.getUniqueId()); if(s!=null) { e.setCancelled(true); back(p,s); }
 }
 @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=false) public void before(PlayerInteractEvent e) {
  if(e.getAction()!=Action.PHYSICAL || e.getClickedBlock()==null) return;
  Player p=e.getPlayer(); ParkourSession s=ParkourSessionManager.getSession(p.getUniqueId()); if(s==null || s.getCompletedCheckpoints()!=s.getMaxCheckpoints()) return;
  try { Location end=query().getEndLocation(s.getParkourName()); if(end!=null && end.getBlock().equals(e.getClickedBlock())) candidates.put(p.getUniqueId(),new Candidate(s,s.getElapsedTimeMillis())); }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Ziel konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false) public void after(PlayerInteractEvent e) {
  if(e.getAction()!=Action.PHYSICAL) return;
  Player p=e.getPlayer(); Candidate candidate=candidates.remove(p.getUniqueId());
  if(candidate!=null && ParkourSessionManager.getSession(p.getUniqueId())!=candidate.session() && candidate.ms()>0) save(p.getUniqueId(),p.getName(),candidate.ms());
 }
 @EventHandler public void quit(PlayerQuitEvent e) { candidates.remove(e.getPlayer().getUniqueId()); }
 private void save(UUID id,String name,long ms) { worker.execute(()->{
  try(Connection c=db(); PreparedStatement s=c.prepareStatement("INSERT INTO parkour_times (uuid,name,best_ms,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE name=IF(VALUES(best_ms)<best_ms,VALUES(name),name), updated_at=IF(VALUES(best_ms)<best_ms,VALUES(updated_at),updated_at), best_ms=LEAST(best_ms,VALUES(best_ms))")) { s.setString(1,id.toString()); s.setString(2,name); s.setLong(3,ms); s.executeUpdate(); refresh(); }
  catch(SQLException ex) { getLogger().log(Level.SEVERE,"Parkour-Bestzeit konnte nicht gespeichert werden fuer "+id,ex); }
 }); }
 private void refresh() { if(worker.isShutdown()) return; worker.execute(()->{
  List<Entry> entries=new ArrayList<>();
  try(Connection c=db(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT name,best_ms FROM parkour_times ORDER BY best_ms ASC, uuid ASC LIMIT 10")) { while(r.next()) entries.add(new Entry(r.getString(1),r.getLong(2))); }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Bestenliste konnte nicht gelesen werden",ex); return; }
  if(isEnabled()) getServer().getScheduler().runTask(this,()->render(entries));
 }); }
 private void render(List<Entry> entries) {
  Location loc=getConfig().getLocation("board.location"); if(loc==null || loc.getWorld()==null) return;
  loc.getChunk().load(); TextDisplay display=null; String id=getConfig().getString("board.entity");
  if(id!=null) try { Entity entity=Bukkit.getEntity(UUID.fromString(id)); if(entity instanceof TextDisplay t) display=t; } catch(IllegalArgumentException ignored) {}
  if(display==null) { loc.getChunk().load(); display=loc.getWorld().spawn(loc,TextDisplay.class,t->{ t.setBillboard(Display.Billboard.CENTER); t.setPersistent(true); t.setSeeThrough(false); }); getConfig().set("board.entity",display.getUniqueId().toString()); saveConfig(); }
  StringBuilder text=new StringBuilder("Parkour - Top 10"); for(int i=0;i<entries.size();i++) text.append("\n").append(i+1).append(". ").append(entries.get(i).name()).append("  ").append(TimeFormat.format(entries.get(i).ms()));
  if(entries.isEmpty()) text.append("\nNoch keine Bestzeiten"); display.text(Component.text(text.toString()));
 }
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
  if(!(sender instanceof Player p)) { sender.sendMessage("Nur im Spiel verfuegbar."); return true; }
  if(args.length==1 && args[0].equalsIgnoreCase("leave")) { if(!ParkourSessionManager.isInSession(p.getUniqueId())) { p.sendMessage("Du bist nicht im Parkour."); return true; } ParkourSessionManager.restoreInventory(p); ParkourSessionManager.endSession(p.getUniqueId()); p.sendMessage("Parkour verlassen."); return true; }
  if(args.length==1 && args[0].equalsIgnoreCase("setboard")) { if(!p.isOp()) { p.sendMessage("Nur OPs duerfen die Bestenliste setzen."); return true; } Location previous=getConfig().getLocation("board.location"); if(previous!=null && previous.getWorld()!=null) previous.getChunk().load(); String old=getConfig().getString("board.entity"); if(old!=null) try { Entity entity=Bukkit.getEntity(UUID.fromString(old)); if(entity instanceof TextDisplay) entity.remove(); } catch(IllegalArgumentException ignored) {} getConfig().set("board.entity",null); getConfig().set("board.location",p.getLocation()); saveConfig(); refresh(); p.sendMessage("Parkour-Bestenliste gesetzt."); return true; }
  p.sendMessage("/parkour leave | /parkour setboard (OP)"); return true;
 }
}
