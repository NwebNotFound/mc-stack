package de.mcstack.parkour;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

/** Administration is restricted to displays owned by this plugin. */
final class HoloAdmin implements CommandExecutor, TabCompleter {
 private final ParkourBridge plugin;
 private final Map<UUID, Pending> pending=new HashMap<>();
 private record Pending(String path, String entityId, Location location, String token, long expires) {}
 private record Board(String path, String id, Location location) {}
 HoloAdmin(ParkourBridge plugin) { this.plugin=plugin; }
 void start() { PluginCommand cmd=Objects.requireNonNull(plugin.getCommand("cc")); cmd.setExecutor(this); cmd.setTabCompleter(this); }
 static String id(String path) { return "pb-"+Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8)); }
 private List<Board> boards() {
  List<Board> result=new ArrayList<>(); add(result,"board");
  for(String root:List.of("boards","retired-boards")) {
   var section=plugin.getConfig().getConfigurationSection(root);
   if(section!=null) for(String key:section.getKeys(false)) add(result,root+"."+key);
  }
  return result;
 }
 private void add(List<Board> out,String path) { Location loc=plugin.getConfig().getLocation(path+".location"); if(loc!=null && loc.getWorld()!=null) out.add(new Board(path,id(path),loc)); }
 private Board find(String id) { return boards().stream().filter(b->b.id().equals(id)).findFirst().orElse(null); }
 private Board nearest(Player p) {
  Location eye=p.getEyeLocation(); Vector direction=eye.getDirection().normalize();
  List<Board> same=boards().stream().filter(b->b.location().getWorld().equals(eye.getWorld())).toList();
  Board looked=same.stream().filter(b->{ Vector delta=b.location().toVector().subtract(eye.toVector()); double along=delta.dot(direction); return along>0 && along<=30 && delta.clone().subtract(direction.clone().multiply(along)).lengthSquared()<=2.25; }).min(Comparator.comparingDouble(b->b.location().distanceSquared(eye))).orElse(null);
  return looked!=null?looked:same.stream().min(Comparator.comparingDouble(b->b.location().distanceSquared(eye))).orElse(null);
 }
 private Entity entity(Board b) {
  b.location().getChunk().load(); String raw=plugin.getConfig().getString(b.path()+".entity");
  try { return raw==null?null:Bukkit.getEntity(UUID.fromString(raw)); } catch(IllegalArgumentException ex) { return null; }
 }
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a) {
  if(!sender.hasPermission("craftcore.holo.admin")) return true;
  if(!(sender instanceof Player p)) { sender.sendMessage("Bitte im Spiel verwenden."); return true; }
  if(a.length>=1 && a[0].equalsIgnoreCase("parkour")) return parkour(p,a);
  if(a.length<2 || !a[0].equalsIgnoreCase("holo")) { p.sendMessage("/cc holo list | remove [id] | move <id>"); return true; }
  if(a[1].equalsIgnoreCase("list") && a.length==2) {
   List<Board> all=boards(); if(all.isEmpty()) p.sendMessage("Keine ParkourBridge-Tafeln vorhanden.");
   for(Board b:all) { String distance=b.location().getWorld().equals(p.getWorld())?String.format(Locale.ROOT,"%.1f m",b.location().distance(p.getLocation())):b.location().getWorld().getName(); p.sendMessage(b.id()+" | ParkourBridge | "+distance+" | "+b.path()); }
   return true;
  }
  if(a[1].equalsIgnoreCase("confirm") && a.length==3) {
   Pending req=pending.remove(p.getUniqueId());
   if(req==null || !req.token().equals(a[2]) || req.expires()<System.currentTimeMillis()) { p.sendMessage("Bestaetigung abgelaufen. Bitte erneut entfernen."); return true; }
   Board b=boards().stream().filter(x->x.path().equals(req.path())).findFirst().orElse(null);
   if(b==null || !Objects.equals(req.entityId(),plugin.getConfig().getString(b.path()+".entity")) || !req.location().equals(b.location())) { p.sendMessage("Tafel wurde inzwischen geaendert. Bitte erneut entfernen."); return true; }
   Entity e=entity(b); if(e!=null && !(e instanceof TextDisplay)) { p.sendMessage("Unerwarteter Entity-Typ; Tafel nicht entfernt."); return true; }
   Map<String,Object> before=new LinkedHashMap<>(plugin.getConfig().getConfigurationSection(b.path()).getValues(true)); plugin.getConfig().set(b.path(),null);
   if(!plugin.saveConfigChecked()) { for(var entry:before.entrySet()) if(!(entry.getValue() instanceof org.bukkit.configuration.ConfigurationSection)) plugin.getConfig().set(b.path()+"."+entry.getKey(),entry.getValue()); p.sendMessage("Tafel konnte nicht gespeichert werden; nicht entfernt."); return true; }
   if(e!=null) e.remove(); for(World world:Bukkit.getWorlds()) for(Chunk chunk:world.getLoadedChunks()) plugin.cleanOrphans(chunk); p.sendMessage("Tafel entfernt: "+b.id()); return true;
  }
  if(a[1].equalsIgnoreCase("remove") && a.length<=3) {
   Board b=a.length==3?find(a[2]):nearest(p); if(b==null) { p.sendMessage("Keine passende Tafel gefunden. Nutze /cc holo list."); return true; }
   String token=UUID.randomUUID().toString(); pending.put(p.getUniqueId(),new Pending(b.path(),plugin.getConfig().getString(b.path()+".entity"),b.location().clone(),token,System.currentTimeMillis()+30000));
   p.sendMessage(Component.text("Tafel "+b.id()+" entfernen? [Hier bestaetigen]").clickEvent(ClickEvent.runCommand("/cc holo confirm "+token))); return true;
  }
  if(a[1].equalsIgnoreCase("move") && a.length==3) {
   Board b=find(a[2]); if(b==null) { p.sendMessage("Tafel nicht gefunden. Nutze /cc holo list."); return true; }
   Entity e=entity(b); if(e!=null && !(e instanceof TextDisplay)) { p.sendMessage("Unerwarteter Entity-Typ; Tafel nicht bewegt."); return true; }
   Location dest=p.getLocation(); if(e!=null && !e.teleport(dest)) { p.sendMessage("Tafel konnte nicht bewegt werden."); return true; }
   plugin.getConfig().set(b.path()+".location",dest); plugin.saveConfig(); p.sendMessage("Tafel verschoben: "+b.id()); return true;
  }
  p.sendMessage("/cc holo list | remove [id] | move <id>"); return true;
 }
 private boolean parkour(Player p,String[] a) {
  if(a.length<3 || !(a[1].equalsIgnoreCase("rename") || a[1].equalsIgnoreCase("icon"))) { p.sendMessage("/cc parkour rename <id> <Name...> | icon <id> [material]"); return true; }
  String slug=a[2].toLowerCase(Locale.ROOT);
  if(!plugin.courseIds().contains(slug)) { p.sendMessage("Kurs nicht gefunden: "+slug); return true; }
  boolean rename=a[1].equalsIgnoreCase("rename"); String value;
  if(rename) { value=String.join(" ",Arrays.copyOfRange(a,3,a.length)).trim(); if(value.isEmpty() || value.length()>64) { p.sendMessage("Name muss 1 bis 64 Zeichen lang sein."); return true; } }
  else {
   if(a.length>4) { p.sendMessage("Zu viele Argumente."); return true; }
   Material material;
   if(a.length==3) material=p.getInventory().getItemInMainHand().getType();
   else { String icon=ParkourSql.icon(a[3]); material=icon==null?null:Material.matchMaterial(icon); }
   if(material==null || material.isAir() || !material.isItem()) { p.sendMessage("Ungueltiges Material oder kein Item in der Hand."); return true; }
   value=ParkourSql.icon(material.getKey().getKey()); if(value==null) { p.sendMessage("Ungueltiges Material."); return true; }
  }
  try { if(plugin.updateCourse(slug,rename?"display_name":"icon",value)) p.sendMessage("Kurs "+slug+" aktualisiert: "+value); else p.sendMessage("Kurs nicht gefunden: "+slug); }
  catch(java.sql.SQLException ex) { plugin.getLogger().log(java.util.logging.Level.WARNING,"Kurs konnte nicht gespeichert werden",ex); p.sendMessage("Kurs konnte nicht gespeichert werden."); }
  return true;
 }
 @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] a) {
  if(!sender.hasPermission("craftcore.holo.admin")) return List.of();
  List<String> choices=a.length==1?List.of("holo","parkour"):a[0].equalsIgnoreCase("parkour") && a.length==2?List.of("rename","icon"):a[0].equalsIgnoreCase("parkour") && a.length==3 && (a[1].equalsIgnoreCase("rename") || a[1].equalsIgnoreCase("icon"))?plugin.courseIds():a[0].equalsIgnoreCase("parkour") && a.length==4 && a[1].equalsIgnoreCase("icon")?Arrays.stream(Material.values()).filter(m->!m.isLegacy() && m.isItem() && !m.isAir()).map(m->m.getKey().getKey()).filter(x->ParkourSql.icon(x)!=null).sorted().toList():a.length==2 && a[0].equalsIgnoreCase("holo")?List.of("list","remove","move"):a.length==3 && a[0].equalsIgnoreCase("holo") && (a[1].equalsIgnoreCase("remove")||a[1].equalsIgnoreCase("move"))?boards().stream().map(Board::id).toList():List.of();
  return choices.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(a[a.length-1].toLowerCase(Locale.ROOT))).toList();
 }
}
