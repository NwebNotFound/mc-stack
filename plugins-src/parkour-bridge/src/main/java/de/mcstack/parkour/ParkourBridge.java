package de.mcstack.parkour;

import java.sql.*;
import java.nio.file.*;
import java.io.IOException;
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
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class ParkourBridge extends JavaPlugin implements Listener {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<UUID, Candidate> candidates=new HashMap<>();
 private final ParkourSql.FloorCache floors=new ParkourSql.FloorCache();
 private String password;
 private record Candidate(ParkourSession session, String course, long ms) {}

 private Connection db() throws SQLException { return DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000","craftcore",password); }
 @Override public void onEnable() {
  password=System.getenv("CRAFTCORE_DB_PASSWORD");
  if(password==null || password.isBlank()) { getLogger().severe("Datenbankpasswort fehlt"); getServer().getPluginManager().disablePlugin(this); return; }
  try { Class.forName("org.mariadb.jdbc.Driver"); try(Connection c=db(); Statement s=c.createStatement()) { migrate(c,s); } }
  catch(Exception e) { getLogger().log(Level.SEVERE,"Parkour-Datenbank nicht verfuegbar",e); getServer().getPluginManager().disablePlugin(this); return; }
  saveDefaultConfig(); getServer().getPluginManager().registerEvents(this,this); new HoloAdmin(this).start(); removeRetiredBoards(); for(World world:Bukkit.getWorlds()) for(Chunk chunk:world.getLoadedChunks()) cleanOrphans(chunk); refresh();
 }

 boolean saveConfigChecked() {
  Path target=getDataFolder().toPath().resolve("config.yml"), temp=null;
  try {
   Files.createDirectories(target.getParent()); temp=Files.createTempFile(target.getParent(),"config-", ".tmp");
   Files.writeString(temp,getConfig().saveToString());
   Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   return true;
  } catch(IOException ex) { getLogger().log(Level.SEVERE,"Konfiguration konnte nicht gespeichert werden",ex); return false; }
  finally { if(temp!=null) try { Files.deleteIfExists(temp); } catch(IOException ignored) {} }
 }
 Set<UUID> referencedDisplays() {
  Set<UUID> ids=new HashSet<>(); List<String> paths=new ArrayList<>(List.of("board"));
  for(String root:List.of("boards","retired-boards")) { var sec=getConfig().getConfigurationSection(root); if(sec!=null) for(String key:sec.getKeys(false)) paths.add(root+"."+key); }
  for(String path:paths) { String id=getConfig().getString(path+".entity"); if(id!=null) try { ids.add(UUID.fromString(id)); } catch(IllegalArgumentException ignored) {} }
  return ids;
 }
 void cleanOrphans(Chunk chunk) {
  Set<UUID> referenced=referencedDisplays();
  for(Entity e:chunk.getEntities()) if((e instanceof Display || e instanceof ArmorStand) && e.getScoreboardTags().contains("ParkourBridge") && !referenced.contains(e.getUniqueId())) e.remove();
 }
 @EventHandler public void chunkLoad(ChunkLoadEvent event) { cleanOrphans(event.getChunk()); }
 private void migrate(Connection c,Statement s) throws SQLException {
  s.execute(ParkourSql.CREATE); s.execute(ParkourSql.ADD_COURSE); s.execute(ParkourSql.ADD_COURSE_NAME);
  List<String> pk=new ArrayList<>(); try(ResultSet r=s.executeQuery(ParkourSql.PK_COLUMNS)) { while(r.next()) pk.add(r.getString(1)); }
  for(String step:ParkourSql.migration(pk)) { getLogger().info("parkour_times: Primaerschluessel wird auf (uuid, course) umgestellt"); s.execute(step); }
 }
 @Override public void reloadConfig() { super.reloadConfig(); floors.clear(); }
 @Override public void onDisable() { floors.clear(); worker.shutdown(); try { if(!worker.awaitTermination(10,TimeUnit.SECONDS)) getLogger().warning("Ausstehende Parkour-Datenbankaufgaben nicht abgeschlossen"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
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
 /** Kursboden: tiefster Punkt (Start, Checkpoints, Ziel) minus fall-distance-below-floor. Erst darunter wird zurueckgesetzt. */
 private Double floorY(String parkourName) throws SQLException {
  return floors.get(parkourName,()->loadFloorY(parkourName));
 }
 private Double loadFloorY(String parkourName) throws SQLException {
  List<Double> ys=new ArrayList<>(); Connection c=ParkoursDatabase.getConnection();
  try(PreparedStatement p=c.prepareStatement("SELECT start_cp,end_cp FROM parkours WHERE pk_name=?")) { p.setString(1,parkourName); try(ResultSet r=p.executeQuery()) { while(r.next()) { ys.add(ParkourSql.locY(r.getString(1))); ys.add(ParkourSql.locY(r.getString(2))); } } }
  try(PreparedStatement p=c.prepareStatement("SELECT c.location FROM checkpoints c JOIN parkours k ON k.id=c.parkour_id WHERE k.pk_name=?")) { p.setString(1,parkourName); try(ResultSet r=p.executeQuery()) { while(r.next()) ys.add(ParkourSql.locY(r.getString(1))); } }
  return ParkourSql.floor(ys,getConfig().getDouble("fall-distance-below-floor",getConfig().getDouble("fall-distance-below-checkpoint",4)));
 }
 /** Hoechster Y-Wert seit dem letzten Bodenkontakt, pro Spieler im Lauf. */
 private final Map<UUID, Double> peaks=new HashMap<>();
 private void reset(Player p,ParkourSession s,PlayerMoveEvent e) {
  peaks.remove(p.getUniqueId());
  try { Location cp=checkpoint(s); if(cp==null) { p.sendMessage("Parkour: Checkpoint nicht gefunden."); return; } cp.setYaw(s.getLastCheckpointYaw()); cp.setPitch(s.getLastCheckpointPitch()); p.setFallDistance(0); p.setVelocity(new Vector()); if(e!=null) e.setTo(cp); else p.teleport(cp); }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Checkpoint konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void move(PlayerMoveEvent e) {
  Location to=e.getTo(); if(to==null || e.getFrom().getY()==to.getY()) return;
  Player p=e.getPlayer(); ParkourSession s=ParkourSessionManager.getSession(p.getUniqueId()); if(s==null) { peaks.remove(p.getUniqueId()); return; }
  // Wasser beruehrt -> zurueck zum Checkpoint
  if(p.isInWater() || to.getBlock().getType()==Material.WATER) { reset(p,s,e); return; }
  // Echter Fallschaden (vanilla): Fallhoehe > 3 und Landeblock daempft nicht (Schleim, Heu, Honig, Bett, Pulverschnee, Wasser ...)
  boolean climbable=Tag.CLIMBABLE.isTagged(e.getFrom().getBlock().getType()) || Tag.CLIMBABLE.isTagged(to.getBlock().getType())
   || Tag.CLIMBABLE.isTagged(e.getFrom().clone().add(0,1,0).getBlock().getType()) || Tag.CLIMBABLE.isTagged(to.clone().add(0,1,0).getBlock().getType());
  if(climbable || !p.isOnGround()) { Double peak=ParkourRules.nextPeak(peaks.get(p.getUniqueId()),e.getFrom().getY(),to.getY(),false,climbable); if(peak==null) peaks.remove(p.getUniqueId()); else peaks.put(p.getUniqueId(),peak); }
  else { Double peak=peaks.remove(p.getUniqueId()); if(peak!=null) { Block b=to.clone().subtract(0,0.2,0).getBlock(); if(b.getType().isAir()) b=to.clone().subtract(0,1.2,0).getBlock();
   if(ParkourRules.fallResets(peak-to.getY(),b.getType().getKey().getKey(),Tag.BEDS.isTagged(b.getType()))) { reset(p,s,e); return; } } }
  // Fallback: weit unter dem tiefsten Kurspunkt
  try { Location cp=checkpoint(s); Double floor=floorY(s.getParkourName()); if(cp!=null && floor!=null && cp.getWorld().equals(to.getWorld()) && to.getY()<floor) reset(p,s,e); }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Checkpoint konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void damage(EntityDamageEvent e) {
  if(!(e.getEntity() instanceof Player p)) return;
  ParkourSession s=ParkourSessionManager.getSession(p.getUniqueId()); if(s==null) return;
  if(e.getCause()==EntityDamageEvent.DamageCause.VOID) { e.setCancelled(true); reset(p,s,null); return; }
  if(e.getCause()==EntityDamageEvent.DamageCause.FALL) { e.setCancelled(true); Block b=p.getLocation().subtract(0,0.2,0).getBlock(); if(!ParkourRules.softLanding(b.getType().getKey().getKey(),Tag.BEDS.isTagged(b.getType()))) reset(p,s,null); }
 } @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=false) public void before(PlayerInteractEvent e) {
  if(e.getAction()!=Action.PHYSICAL || e.getClickedBlock()==null) return;
  Player p=e.getPlayer(); ParkourSession s=ParkourSessionManager.getSession(p.getUniqueId()); if(s==null || s.getCompletedCheckpoints()!=s.getMaxCheckpoints()) return;
  try { Location end=query().getEndLocation(s.getParkourName()); if(end!=null && end.getBlock().equals(e.getClickedBlock())) { String course=s.getParkourName(); if(ParkourSql.course(course)!=null && ParkourSql.course(course).length()<=64) candidates.put(p.getUniqueId(),new Candidate(s,course,s.getElapsedTimeMillis())); } }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Ziel konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false) public void after(PlayerInteractEvent e) {
  if(e.getAction()!=Action.PHYSICAL) return;
  Player p=e.getPlayer(); Candidate candidate=candidates.remove(p.getUniqueId());
  if(candidate!=null && ParkourSessionManager.getSession(p.getUniqueId())!=candidate.session() && candidate.ms()>0) save(p.getUniqueId(),candidate.course(),p.getName(),candidate.ms());
 }
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void courseCommand(PlayerCommandPreprocessEvent e) { if(ParkourRules.isCourseCommand(e.getMessage())) floors.clear(); }
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void courseConsoleCommand(ServerCommandEvent e) { if(ParkourRules.isCourseCommand(e.getCommand())) floors.clear(); }
 @EventHandler public void quit(PlayerQuitEvent e) { candidates.remove(e.getPlayer().getUniqueId()); peaks.remove(e.getPlayer().getUniqueId()); }
 private void save(UUID id,String course,String name,long ms) { worker.execute(()->{
  try(Connection c=db(); PreparedStatement s=c.prepareStatement(ParkourSql.UPSERT)) { s.setString(1,id.toString()); s.setString(2,ParkourSql.course(course)); s.setString(3,course); s.setString(4,name); s.setLong(5,ms); s.executeUpdate(); refresh(); }
  catch(SQLException ex) { getLogger().log(Level.SEVERE,"Parkour-Bestzeit konnte nicht gespeichert werden fuer "+id,ex); }
 }); }
 private void refresh() { if(worker.isShutdown()) return; worker.execute(()->{
  Map<String,List<ParkourSql.Entry>> byCourse=new TreeMap<>();
  try(Connection c=db(); Statement s=c.createStatement(); ResultSet r=s.executeQuery(ParkourSql.TOP)) { Map<String,Set<String>> seen=new HashMap<>(); while(r.next()) { String course=ParkourSql.course(r.getString(1)); if(course==null) continue; List<ParkourSql.Entry> entries=byCourse.computeIfAbsent(course,k->new ArrayList<>()); if(entries.size()<10 && seen.computeIfAbsent(course,k->new HashSet<>()).add(r.getString(2))) entries.add(new ParkourSql.Entry(r.getString(3),r.getLong(4))); } }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Bestenliste konnte nicht gelesen werden",ex); return; }
  if(isEnabled()) getServer().getScheduler().runTask(this,()->renderAll(byCourse));
 }); }
 /** "board" = Sammeltafel aller Kurse (bisherige Tafel), "boards.<kurs>" = Top 10 eines Kurses. */
 private void removeRetiredBoards() {
  var section=getConfig().getConfigurationSection("retired-boards"); if(section==null) return;
  for(String key:new ArrayList<>(section.getKeys(false))) {
   String path="retired-boards."+key; Location loc=getConfig().getLocation(path+".location");
   if(loc==null || loc.getWorld()==null) { getLogger().warning("Alte Tafel kann nicht entfernt werden: "+key); continue; }
   loc.getChunk().load(); String id=getConfig().getString(path+".entity");
   try { Entity entity=id==null?null:Bukkit.getEntity(UUID.fromString(id)); if(entity!=null && !(entity instanceof TextDisplay)) { getLogger().warning("Alte Tafel hat unerwarteten Entity-Typ: "+key); continue; } if(entity!=null) entity.remove(); getConfig().set(path,null); }
   catch(IllegalArgumentException ex) { getLogger().warning("Ungueltige Tafel-ID: "+key); }
  }
  saveConfig();
 }
 private void renderAll(Map<String,List<ParkourSql.Entry>> byCourse) {
  render("board",ParkourSql.allBoards(byCourse));
  var sec=getConfig().getConfigurationSection("boards");
  if(sec!=null) for(String key:sec.getKeys(false)) { String path="boards."+key; String name=getConfig().getString(path+".course",key); String course=ParkourSql.course(name); render(path,ParkourSql.board(name,byCourse.getOrDefault(course,List.of()))); }
 }
 private void render(String key,String text) {
  Location loc=getConfig().getLocation(key+".location"); if(loc==null || loc.getWorld()==null) return;
  loc.getChunk().load(); TextDisplay display=null; String id=getConfig().getString(key+".entity");
  if(id!=null) try { Entity entity=Bukkit.getEntity(UUID.fromString(id)); if(entity instanceof TextDisplay t) display=t; } catch(IllegalArgumentException ignored) {}
  if(display==null) { display=loc.getWorld().spawn(loc,TextDisplay.class,t->{ t.setBillboard(Display.Billboard.CENTER); t.setPersistent(true); t.addScoreboardTag("ParkourBridge"); t.setSeeThrough(false); }); getConfig().set(key+".entity",display.getUniqueId().toString()); saveConfig(); }
  display.addScoreboardTag("ParkourBridge"); display.text(Component.text(text));
 }
 private String actualCourse(String requested) throws SQLException {
  try(PreparedStatement s=ParkoursDatabase.getConnection().prepareStatement("SELECT pk_name FROM parkours WHERE lower(pk_name)=lower(?)")) { s.setString(1,requested); try(ResultSet r=s.executeQuery()) { return r.next()?r.getString(1):null; } }
 }
 private void setBoard(Player p,String key) {
  Location previous=getConfig().getLocation(key+".location"); if(previous!=null && previous.getWorld()!=null) previous.getChunk().load();
  String old=getConfig().getString(key+".entity"); if(old!=null) try { Entity entity=Bukkit.getEntity(UUID.fromString(old)); if(entity instanceof TextDisplay) entity.remove(); } catch(IllegalArgumentException ignored) {}
  getConfig().set(key+".entity",null); getConfig().set(key+".location",p.getLocation()); saveConfig(); refresh();
 }
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
  if(!(sender instanceof Player p)) { sender.sendMessage("Nur im Spiel verfuegbar."); return true; }
  if(args.length==1 && args[0].equalsIgnoreCase("leave")) { if(!ParkourSessionManager.isInSession(p.getUniqueId())) { p.sendMessage("Du bist nicht im Parkour."); return true; } ParkourSessionManager.restoreInventory(p); ParkourSessionManager.endSession(p.getUniqueId()); p.sendMessage("Parkour verlassen."); return true; }
  if(args.length>=1 && args[0].equalsIgnoreCase("setboard")) {
   if(!p.isOp()) { p.sendMessage("Nur OPs duerfen die Bestenliste setzen."); return true; }
   if(args.length==1) { setBoard(p,"board"); p.sendMessage("Parkour-Bestenliste (alle Kurse) gesetzt."); return true; }
   String requested=ParkourSql.argument(args);
   if(requested==null) { p.sendMessage("Ungueltiger Kursname."); return true; }
   try {
    String name=actualCourse(requested); if(name==null) { p.sendMessage("Kurs nicht gefunden: "+requested); return true; }
    String key=ParkourSql.boardKey(name); String path="boards."+key;
    getConfig().set(path+".course",name); setBoard(p,path); p.sendMessage("Parkour-Bestenliste fuer Kurs "+name+" gesetzt."); return true;
   } catch(SQLException ex) { getLogger().log(Level.WARNING,"Kurs konnte nicht gelesen werden",ex); p.sendMessage("Kurs konnte nicht gelesen werden."); return true; }
  }
  p.sendMessage("/parkour leave | /parkour setboard [kurs] (OP)"); return true;
 }
}
