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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class ParkourBridge extends JavaPlugin implements Listener {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<UUID, Candidate> candidates=new HashMap<>();
 private final ParkourSql.FloorCache floors=new ParkourSql.FloorCache();
 private String password;
 private boolean configReady;
 private record Candidate(ParkourSession session, String course, long ms) {}
 private record AdminCourse(int id,String name,String slug,String display) {}
 private volatile List<AdminCourse> adminCourses=List.of();
 private volatile Map<String,List<String>> adminPlayers=Map.of();
 private final java.util.concurrent.atomic.AtomicBoolean loadingAdminCompletions=new java.util.concurrent.atomic.AtomicBoolean();

 private Connection db() throws SQLException { return DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000","craftcore",password); }
 @Override public void onEnable() {
  password=System.getenv("CRAFTCORE_DB_PASSWORD");
  if(password==null || password.isBlank()) { getLogger().severe("Datenbankpasswort fehlt"); getServer().getPluginManager().disablePlugin(this); return; }
  try { Class.forName("org.mariadb.jdbc.Driver"); try(Connection c=db(); Statement s=c.createStatement()) { migrate(c,s); } }
  catch(Exception e) { getLogger().log(Level.SEVERE,"Parkour-Datenbank nicht verfuegbar",e); getServer().getPluginManager().disablePlugin(this); return; }
  saveDefaultConfig();
  getConfig(); configReady=true; warnInvalidWaterMode();
  var floorSection=getConfig().getConfigurationSection("course-floors");
  if(floorSection!=null && !floorSection.getKeys(false).isEmpty()) getLogger().info("course-floors aktiv: "+floorSection.getValues(false));
  getServer().getPluginManager().registerEvents(this,this); new HoloAdmin(this).start(); removeRetiredBoards(); for(World world:Bukkit.getWorlds()) for(Chunk chunk:world.getLoadedChunks()) cleanOrphans(chunk); refresh(); loadAdminCompletions();
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
  for(String sql:List.of(ParkourSql.CREATE_COURSES,ParkourSql.ADD_DISPLAY,ParkourSql.ADD_ICON,ParkourSql.SEED_COURSES,ParkourSql.CAVE_NAME,ParkourSql.VIEW_COURSES)) s.execute(sql);
  try { syncCourses(c); } catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Kurse konnten nicht synchronisiert werden",ex); }
 }
 private void syncCourses(Connection c) throws SQLException {
  try(Statement source=ParkoursDatabase.getConnection().createStatement(); ResultSet rows=source.executeQuery("SELECT pk_name FROM parkours"); PreparedStatement insert=c.prepareStatement("INSERT INTO parkour_courses (slug) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM parkour_courses WHERE slug=?)")) {
   while(rows.next()) { String slug=ParkourSql.course(rows.getString(1)); if(slug!=null) { insert.setString(1,slug); insert.setString(2,slug); insert.executeUpdate(); } }
  }
  try(Statement s=c.createStatement()) { s.executeUpdate(ParkourSql.CAVE_NAME); }
 }
 List<String> courseIds() {
  try(Connection c=db()) { syncCourses(c); try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT slug FROM parkour_courses ORDER BY slug")) { List<String> ids=new ArrayList<>(); while(r.next()) ids.add(r.getString(1)); return ids; } }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Kurse konnten nicht gelesen werden",ex); return List.of(); }
 }
 boolean updateCourse(String slug,String field,String value) throws SQLException {
  if(!List.of("display_name","icon").contains(field)) throw new IllegalArgumentException(field);
  try(Connection c=db(); PreparedStatement s=c.prepareStatement("UPDATE parkour_courses SET "+field+"=? WHERE slug=?")) { s.setString(1,value); s.setString(2,slug); boolean found=s.executeUpdate()>0; if(found) refresh(); return found; }

 }
 private void warnInvalidWaterMode() {
  String raw=getConfig().getString("water-reset");
  if(raw!=null && !ParkourRules.validWaterMode(raw)) getLogger().warning("Unbekannter water-reset-Wert '"+raw+"', verwende feet");
 }
 @Override public void reloadConfig() { super.reloadConfig(); floors.clear(); if(configReady) warnInvalidWaterMode(); }
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
 /** Kursboden: konfigurierte Grenze oder tiefster Kurspunkt minus fall-distance-below-floor. */
 private Double floorY(String parkourName) throws SQLException {
  return floors.get(parkourName,()->loadFloorY(parkourName));
 }
 private Double loadFloorY(String parkourName) throws SQLException {
  var section=getConfig().getConfigurationSection("course-floors");
  Double override=ParkourSql.courseFloor(section==null?Map.of():section.getValues(false),parkourName);
  if(override!=null) { getLogger().info("Kursboden fuer "+parkourName+": "+override+" (course-floors)"); return ParkourSql.effectiveFloor(null,override); }
  List<Double> ys=new ArrayList<>(); Connection c=ParkoursDatabase.getConnection();
  try(PreparedStatement p=c.prepareStatement("SELECT start_cp,end_cp FROM parkours WHERE pk_name=?")) { p.setString(1,parkourName); try(ResultSet r=p.executeQuery()) { while(r.next()) { ys.add(ParkourSql.locY(r.getString(1))); ys.add(ParkourSql.locY(r.getString(2))); } } }
  try(PreparedStatement p=c.prepareStatement("SELECT c.location FROM checkpoints c JOIN parkours k ON k.id=c.parkour_id WHERE k.pk_name=?")) { p.setString(1,parkourName); try(ResultSet r=p.executeQuery()) { while(r.next()) ys.add(ParkourSql.locY(r.getString(1))); } }
  return ParkourSql.effectiveFloor(ParkourSql.floor(ys,getConfig().getDouble("fall-distance-below-floor",getConfig().getDouble("fall-distance-below-checkpoint",4))),override);
 }
 /** Hoechster Y-Wert seit dem letzten Bodenkontakt, pro Spieler im Lauf. */
 private final Map<UUID, Double> peaks=new HashMap<>();
 private final Map<UUID, Long> bounceGrace=new HashMap<>();
 private record Landing(List<String> keys, boolean bed) {
  boolean soft() { return ParkourRules.softLandingAny(keys,bed); }
  boolean slime() { return keys.contains("slime_block"); }
 }
 private Landing landing(Location location,boolean centerFallback) {
  List<String> keys=new ArrayList<>(); boolean bed=false; int i=0;
  for(double[] point:ParkourRules.footprint(location.getX(),location.getZ())) {
   Location sample=location.clone(); sample.setX(point[0]); sample.setZ(point[1]); sample.subtract(0,0.2,0);
   Block block=sample.getBlock(); if(i++==0 && centerFallback && block.getType().isAir()) block=sample.subtract(0,1,0).getBlock();
   keys.add(block.getType().getKey().getKey()); bed|=Tag.BEDS.isTagged(block.getType());
  }
  return new Landing(keys,bed);
 }
 private void slimeGrace(UUID id,Landing landing,long now) { if(landing.slime()) { peaks.remove(id); bounceGrace.put(id,now+1500); } }
 private void reset(Player p,ParkourSession s,PlayerMoveEvent e) {
  peaks.remove(p.getUniqueId());
  try { Location cp=checkpoint(s); if(cp==null) { p.sendMessage("Parkour: Checkpoint nicht gefunden."); return; } cp.setYaw(s.getLastCheckpointYaw()); cp.setPitch(s.getLastCheckpointPitch()); p.setFallDistance(0); p.setVelocity(new Vector()); if(e!=null) e.setTo(cp); else p.teleport(cp); }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Checkpoint konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void move(PlayerMoveEvent e) {
  Location to=e.getTo(); if(to==null || e.getFrom().getY()==to.getY()) return;
  Player p=e.getPlayer(); ParkourSession s=ParkourSessionManager.getSession(p.getUniqueId()); if(s==null) { peaks.remove(p.getUniqueId()); return; }
  // Wasser am Fuss bzw. je nach Einstellung bei Beruehrung -> zurueck zum Checkpoint
  Block feet=to.getBlock(); BlockData data=feet.getBlockData(); boolean water=feet.getType()==Material.WATER;
  String waterMode=ParkourRules.waterMode(getConfig().getString("water-reset"));
  boolean feetWater=water || (!"touch".equalsIgnoreCase(waterMode) && data instanceof Waterlogged logged && logged.isWaterlogged());
  int waterLevel=water && data instanceof Levelled levelled ? levelled.getLevel() : 0;
  if(ParkourRules.waterReset(waterMode,p.isInWater(),feetWater,waterLevel,to.getY()-feet.getY())) { reset(p,s,e); return; }
  // Optionales Ruecksetzen bei harten Landungen; weiche Bloecke und Schonzeit bleiben ausgenommen.
  boolean climbable=Tag.CLIMBABLE.isTagged(e.getFrom().getBlock().getType()) || Tag.CLIMBABLE.isTagged(to.getBlock().getType())
   || Tag.CLIMBABLE.isTagged(e.getFrom().clone().add(0,1,0).getBlock().getType()) || Tag.CLIMBABLE.isTagged(to.clone().add(0,1,0).getBlock().getType());
  if(climbable || !p.isOnGround()) { Double peak=ParkourRules.nextPeak(peaks.get(p.getUniqueId()),e.getFrom().getY(),to.getY(),false,climbable); if(peak==null) peaks.remove(p.getUniqueId()); else peaks.put(p.getUniqueId(),peak); }
  else { Double peak=peaks.remove(p.getUniqueId()); Landing landing=landing(to,true); long now=System.currentTimeMillis();
   if(peak!=null && ParkourRules.hardLandingReset(peak-to.getY(),getConfig().getDouble("hard-landing-reset-blocks",0),landing.soft(),ParkourRules.inGrace(bounceGrace.get(p.getUniqueId()),now))) { reset(p,s,e); return; }
   slimeGrace(p.getUniqueId(),landing,now); }
  // Fallback: weit unter dem tiefsten Kurspunkt
  try { Location cp=checkpoint(s); Double floor=floorY(s.getParkourName()); if(cp!=null && floor!=null && cp.getWorld().equals(to.getWorld()) && to.getY()<floor) reset(p,s,e); }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Checkpoint konnte nicht gelesen werden",ex); }
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void damage(EntityDamageEvent e) {
  if(!(e.getEntity() instanceof Player p)) return;
  ParkourSession s=ParkourSessionManager.getSession(p.getUniqueId()); if(s==null) return;
  if(e.getCause()==EntityDamageEvent.DamageCause.VOID) { e.setCancelled(true); reset(p,s,null); return; }
  if(e.getCause()==EntityDamageEvent.DamageCause.FALL) { e.setCancelled(true); Landing landing=landing(p.getLocation(),false); long now=System.currentTimeMillis(); if(ParkourRules.hardLandingReset(p.getFallDistance(),getConfig().getDouble("hard-landing-reset-blocks",0),landing.soft(),ParkourRules.inGrace(bounceGrace.get(p.getUniqueId()),now))) reset(p,s,null); else slimeGrace(p.getUniqueId(),landing,now); }
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
 @EventHandler public void quit(PlayerQuitEvent e) { candidates.remove(e.getPlayer().getUniqueId()); peaks.remove(e.getPlayer().getUniqueId()); bounceGrace.remove(e.getPlayer().getUniqueId()); }
 private void save(UUID id,String course,String name,long ms) { worker.execute(()->{
  try(Connection c=db()) { try(PreparedStatement add=c.prepareStatement("INSERT INTO parkour_courses (slug) VALUES (?) ON DUPLICATE KEY UPDATE slug=VALUES(slug)")) { add.setString(1,ParkourSql.course(course)); add.executeUpdate(); } try(PreparedStatement s=c.prepareStatement(ParkourSql.UPSERT)) { s.setString(1,id.toString()); s.setString(2,ParkourSql.course(course)); s.setString(3,course); s.setString(4,name); s.setLong(5,ms); s.executeUpdate(); refresh(); } }
  catch(SQLException ex) { getLogger().log(Level.SEVERE,"Parkour-Bestzeit konnte nicht gespeichert werden fuer "+id,ex); }
 }); }
 private void refresh() { if(worker.isShutdown()) return; worker.execute(()->{
  Map<String,List<ParkourSql.Entry>> byCourse=new TreeMap<>(); Map<String,String> names=new HashMap<>();
  try(Connection c=db(); Statement s=c.createStatement()) { try(ResultSet n=s.executeQuery("SELECT slug,course_name FROM v_parkour_courses")) { while(n.next()) names.put(n.getString(1),n.getString(2)); } try(ResultSet r=s.executeQuery(ParkourSql.TOP)) { Map<String,Set<String>> seen=new HashMap<>(); while(r.next()) { String course=ParkourSql.course(r.getString(1)); if(course==null) continue; List<ParkourSql.Entry> entries=byCourse.computeIfAbsent(course,k->new ArrayList<>()); if(entries.size()<10 && seen.computeIfAbsent(course,k->new HashSet<>()).add(r.getString(2))) entries.add(new ParkourSql.Entry(r.getString(3),r.getLong(4))); } } }
  catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Bestenliste konnte nicht gelesen werden",ex); return; }
  if(isEnabled()) getServer().getScheduler().runTask(this,()->renderAll(byCourse,names));
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
 private void renderAll(Map<String,List<ParkourSql.Entry>> byCourse,Map<String,String> names) {
  StringJoiner boards=new StringJoiner("\n\n"); byCourse.forEach((slug,entries)->boards.add(ParkourSql.board(ParkourSql.displayName(slug,names.get(slug)),entries)));
  render("board",byCourse.isEmpty()?ParkourSql.allBoards(Map.of()):boards.toString());
  var sec=getConfig().getConfigurationSection("boards");
  if(sec!=null) for(String key:sec.getKeys(false)) { String path="boards."+key; String name=getConfig().getString(path+".course",key); String course=ParkourSql.course(name); render(path,ParkourSql.board(ParkourSql.displayName(course,names.get(course)),byCourse.getOrDefault(course,List.of()))); }
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
 /** Main thread only: Lobby Parkour's shared SQLite connection is not thread-safe. */
 private List<Map.Entry<Integer,String>> lobbyCourses() throws SQLException {
  List<Map.Entry<Integer,String>> result=new ArrayList<>();
  try(Statement s=ParkoursDatabase.getConnection().createStatement(); ResultSet r=s.executeQuery("SELECT id,pk_name FROM parkours ORDER BY pk_name")) { while(r.next()) result.add(Map.entry(r.getInt(1),r.getString(2))); }
  return result;
 }
 private List<AdminCourse> readAdminCourses(Connection c,List<Map.Entry<Integer,String>> lobby) throws SQLException {
  Map<String,String> displays=new HashMap<>();
  try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT slug,display_name FROM parkour_courses")) { while(r.next()) displays.put(r.getString(1).toLowerCase(Locale.ROOT),r.getString(2)); }
  List<AdminCourse> courses=new ArrayList<>();
  for(var e:lobby) { String name=e.getValue(),slug=ParkourSql.course(name); if(slug!=null) courses.add(new AdminCourse(e.getKey(),name,slug,displays.get(slug))); }
  return courses;
 }
 private AdminCourse resolveCourse(List<AdminCourse> courses,String requested) throws SQLException {
  if(requested==null) return null;
  List<AdminCourse> matches=courses.stream().filter(c->c.name().equalsIgnoreCase(requested) || c.slug().equalsIgnoreCase(requested) || (c.display()!=null && c.display().equalsIgnoreCase(requested))).toList();
  if(matches.size()>1) throw new SQLException("Mehrdeutiger Kursname: "+requested);
  return matches.isEmpty()?null:matches.get(0);
 }
 private String placeholders(int count) { return String.join(",",Collections.nCopies(count,"?")); }
 private int bindVariants(PreparedStatement statement,Set<String> variants) throws SQLException { int i=1; for(String variant:variants) statement.setString(i++,variant); return i; }
 private void reply(CommandSender sender,String message) { if(isEnabled()) getServer().getScheduler().runTask(this,()->sender.sendMessage(message)); }
 private volatile long adminCompletionsLoadedAt;
 private void loadAdminCompletions() {
  if(worker.isShutdown() || !loadingAdminCompletions.compareAndSet(false,true)) return;
  List<Map.Entry<Integer,String>> lobby;
  try { lobby=lobbyCourses(); } catch(SQLException ex) { loadingAdminCompletions.set(false); getLogger().log(Level.WARNING,"Parkour-Kurse konnten nicht gelesen werden",ex); return; }
  worker.execute(()->{ try(Connection c=db()) {
   List<AdminCourse> courses=readAdminCourses(c,lobby); Map<String,List<String>> players=new HashMap<>();
   try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT DISTINCT course,name FROM parkour_times ORDER BY name")) {
    while(r.next()) { String slug=ParkourSql.course(r.getString(1)); if(slug!=null) players.computeIfAbsent(slug,k->new ArrayList<>()).add(r.getString(2)); }
   }
   adminCourses=List.copyOf(courses); adminPlayers=Map.copyOf(players); adminCompletionsLoadedAt=System.currentTimeMillis();
  } catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Vervollstaendigung konnte nicht geladen werden",ex); } finally { loadingAdminCompletions.set(false); } });
 }
 private void adminCommand(CommandSender sender,String[] args) {
  String action=args[0].toLowerCase(Locale.ROOT);
  if(action.equals("resetall")) {
   if(args.length!=2 || !ParkourAdmin.confirmed(args)) { sender.sendMessage("Bestaetigung: /parkour resetall confirm"); return; }
   worker.execute(()->deleteAdminTimes(sender,null,null)); return;
  }
  if(args.length<2) { sender.sendMessage("Verwendung: /parkour "+action+" <Kurs>"+(action.equals("removetime")?" <Spieler>":"")); return; }
  boolean confirm=action.equals("reset") && ParkourAdmin.confirmed(args);
  int end=confirm?args.length-1:args.length;
  String full=ParkourAdmin.course(args,1,end);
  // Capture Bukkit's offline UUID on the main thread; the stored UUID takes precedence.
  String last=end>2?args[end-1].replace("\"",""):null;
  // getOfflinePlayerIfCached never blocks the main thread with a Mojang lookup.
  OfflinePlayer cached=last!=null && !action.equals("times")?Bukkit.getOfflinePlayerIfCached(last):null;
  UUID fallback=cached==null?null:cached.getUniqueId();
  List<Map.Entry<Integer,String>> lobby;
  try { lobby=lobbyCourses(); } catch(SQLException ex) { getLogger().log(Level.WARNING,"Parkour-Kurse konnten nicht gelesen werden",ex); sender.sendMessage("Parkour-Kurse konnten nicht gelesen werden."); return; }
  worker.execute(()->{
   try(Connection c=db()) {
    List<AdminCourse> courses=readAdminCourses(c,lobby);
    AdminCourse course=resolveCourse(courses,full); String player=null;
    if(!action.equals("times") && end>2 && course==null) {
     String part=ParkourAdmin.course(args,1,end-1); course=resolveCourse(courses,part); player=last;
    }
    if(course==null) { reply(sender,"Kurs nicht gefunden: "+full); return; }
    if(action.equals("times")) { listAdminTimes(sender,c,course); return; }
    if(action.equals("removetime") && player==null) { reply(sender,"Verwendung: /parkour removetime <Kurs> <Spieler>"); return; }
    if(action.equals("reset") && !confirm) { reply(sender,"Bestaetigung: /parkour reset "+course.name()+(player==null?"":" "+player)+" confirm"); return; }
    String uuid=null;
    if(player!=null) {
     Set<String> variants=ParkourAdmin.variants(course.name());
     try(PreparedStatement q=c.prepareStatement("SELECT uuid FROM parkour_times WHERE course IN ("+placeholders(variants.size())+") AND LOWER(name)=LOWER(?) ORDER BY updated_at DESC LIMIT 1")) {
      int i=bindVariants(q,variants); q.setString(i,player); try(ResultSet r=q.executeQuery()) { uuid=r.next()?r.getString(1):fallback==null?null:fallback.toString(); }
     }
     if(uuid==null) { reply(sender,"Spieler nicht gefunden: "+player); return; }
    }
    deleteAdminTimes(sender,course,uuid);
   } catch(Exception ex) { getLogger().log(Level.WARNING,"Parkour-Verwaltung fehlgeschlagen",ex); reply(sender,"Parkour-Verwaltung fehlgeschlagen: "+ex.getMessage()); }
  });
 }
 private void listAdminTimes(CommandSender sender,Connection c,AdminCourse course) throws SQLException {
  Set<String> variants=ParkourAdmin.variants(course.name()); List<String> lines=new ArrayList<>();
  try(PreparedStatement q=c.prepareStatement("SELECT uuid,name,best_ms FROM parkour_times WHERE course IN ("+placeholders(variants.size())+") AND best_ms>0 ORDER BY best_ms ASC, updated_at ASC, uuid ASC")) {
   bindVariants(q,variants); Set<String> seen=new HashSet<>(); try(ResultSet r=q.executeQuery()) { while(r.next()) if(seen.add(r.getString(1))) lines.add((lines.size()+1)+". "+r.getString(2)+"  "+ParkourAdmin.format(r.getLong(3))); }
  }
  reply(sender,"Bestzeiten fuer "+course.name()+":\n"+(lines.isEmpty()?"Noch keine Bestzeiten":String.join("\n",lines)));
 }
 private void deleteAdminTimes(CommandSender sender,AdminCourse course,String uuid) {
  int craft=-1; String error=null;
  try(Connection c=db()) {
   Set<String> variants=course==null?Set.of():ParkourAdmin.variants(course.name());
   String sql="DELETE FROM parkour_times"+(course==null?"":" WHERE course IN ("+placeholders(variants.size())+")"+(uuid==null?"":" AND uuid=?"));
   try(PreparedStatement q=c.prepareStatement(sql)) { int i=bindVariants(q,variants); if(uuid!=null) q.setString(i,uuid); craft=q.executeUpdate(); }
  } catch(SQLException ex) { error="CraftCore: "+ex.getMessage(); getLogger().log(Level.WARNING,"CraftCore-Zeiten konnten nicht geloescht werden",ex); }
  int craftCount=craft; String craftError=error;
  // Lobby Parkour's shared SQLite connection is used on the main thread, so only touch it there.
  if(isEnabled()) getServer().getScheduler().runTask(this,()->deleteLobbyTimes(sender,course,uuid,craftCount,craftError));
 }
 private void deleteLobbyTimes(CommandSender sender,AdminCourse course,String uuid,int craft,String error) {
  int lobby=-1;
  try {
   // Lobby Parkour may cache best times in memory until restart; direct SQLite deletes cannot invalidate that cache.
   String sql="DELETE FROM times"+(course==null?"":" WHERE parkour_id=?"+(uuid==null?"":" AND uuid=?"));
   try(PreparedStatement q=ParkoursDatabase.getConnection().prepareStatement(sql)) { if(course!=null) q.setInt(1,course.id()); if(uuid!=null) q.setString(2,uuid); lobby=q.executeUpdate(); }
  } catch(SQLException ex) { error=(error==null?"":error+"; ")+"Lobby Parkour: "+ex.getMessage(); getLogger().log(Level.WARNING,"Lobby-Parkour-Zeiten konnten nicht geloescht werden",ex); }
  sender.sendMessage("Zeiten geloescht: CraftCore="+(craft<0?"Fehler":craft)+", Lobby Parkour="+(lobby<0?"Fehler":lobby)+(error==null?"":" ("+error+")"));
  refresh(); loadAdminCompletions();
 }
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
  if(args.length>0 && List.of("times","reset","removetime","resetall").contains(args[0].toLowerCase(Locale.ROOT))) {
   if(!sender.hasPermission("craftcore.parkour.admin")) { sender.sendMessage("Keine Berechtigung."); return true; }
   adminCommand(sender,args); return true;
  }
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
  p.sendMessage(Component.text("Parkour: ").append(Component.text("[Lauf verlassen]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/parkour leave"))).append(Component.text(" - Starte einen Kurs am Startpunkt in der Lobby."))); return true;
 }
 @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
  if(args.length==1) {
   List<String> choices=new ArrayList<>(List.of("leave")); if(sender.isOp()) choices.add("setboard");
   if(sender.hasPermission("craftcore.parkour.admin")) choices.addAll(List.of("times","reset","removetime","resetall"));
   return choices.stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
  }
  String action=args[0].toLowerCase(Locale.ROOT);
  if(action.equals("resetall") && sender.hasPermission("craftcore.parkour.admin")) return args.length==2 && "confirm".startsWith(args[1].toLowerCase(Locale.ROOT))?List.of("confirm"):List.of();
  if(!(sender.hasPermission("craftcore.parkour.admin") && List.of("times","reset","removetime").contains(action)) && !(sender.isOp() && action.equals("setboard"))) return List.of();
  if(System.currentTimeMillis()-adminCompletionsLoadedAt>30_000) loadAdminCompletions();
  String prefix=ParkourAdmin.course(args,1,args.length); if(prefix==null) prefix="";
  String lower=prefix.toLowerCase(Locale.ROOT); String raw=String.join(" ",Arrays.copyOfRange(args,1,args.length)).toLowerCase(Locale.ROOT); List<String> result=new ArrayList<>();
  for(AdminCourse c:adminCourses) {
   String name=c.name();
   if(name.toLowerCase(Locale.ROOT).startsWith(lower)) {
    String prior=String.join(" ",Arrays.copyOfRange(args,1,args.length-1));
    int offset=prior.isEmpty()?0:prior.length()+1; if(offset<=name.length()) result.add(name.substring(offset));
   }
   if((action.equals("reset") || action.equals("removetime")) && raw.startsWith(name.toLowerCase(Locale.ROOT)+" ")) {
    String playerPrefix=raw.substring(name.length()+1);
    if(!playerPrefix.contains(" ")) {
     if(action.equals("reset") && "confirm".startsWith(playerPrefix)) result.add("confirm");
     for(String player:adminPlayers.getOrDefault(c.slug(),List.of())) if(player.toLowerCase(Locale.ROOT).startsWith(playerPrefix)) result.add(player);
    }
   }
  }
  return result.stream().distinct().toList();
 }

}
