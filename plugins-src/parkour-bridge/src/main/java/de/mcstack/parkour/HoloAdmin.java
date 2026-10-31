package de.mcstack.parkour;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

/** Administration is restricted to displays owned by this plugin. */
final class HoloAdmin implements CommandExecutor, TabCompleter {
 private final ParkourBridge plugin;
 private final Function<String,Material> materialLookup;
 private final Function<Material,MaterialIcon> materialIcon;
 record MaterialIcon(boolean valid, String value) {}
 private final Map<UUID, Pending> pending=new HashMap<>();
 private record Pending(String path, String entityId, Location location, String token, long expires) {}
 private record Board(String path, String id, Location location) {}
 HoloAdmin(ParkourBridge plugin) { this(plugin,Material::matchMaterial,m->m==null || m.isAir() || !m.isItem()?new MaterialIcon(false,null):new MaterialIcon(true,ParkourSql.icon(m.getKey().getKey()))); }
 HoloAdmin(ParkourBridge plugin,Function<String,Material> materialLookup,Function<Material,MaterialIcon> materialIcon) { this.plugin=plugin; this.materialLookup=materialLookup; this.materialIcon=materialIcon; }
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
  boolean rename=a[1].equalsIgnoreCase("rename"); String value,slug; Map<String,String> names=plugin.courseNames();
  List<String> tokens=Arrays.asList(a).subList(2,a.length); List<String> typed=tokens;
  if(rename) {
   CourseArgs.Match match=CourseArgs.longestPrefix(tokens,names);
   if(match==null) {
    if(CourseArgs.resolve(tokens,names)!=null) p.sendMessage("Name muss 1 bis 64 Zeichen lang sein.");
    else p.sendMessage("Kurs nicht gefunden: "+String.join(" ",tokens));
    return true;
   }
   slug=match.slug(); typed=tokens.subList(0,match.length()); value=String.join(" ",tokens.subList(match.length(),tokens.size())).trim();
   if(value.isEmpty() || value.length()>64) { p.sendMessage("Name muss 1 bis 64 Zeichen lang sein."); return true; }
  } else {
   boolean hand=tokens.size()==1;
   typed=hand?tokens:tokens.subList(0,tokens.size()-1); slug=CourseArgs.resolve(typed,names);
   if(slug==null && !hand) { String all=CourseArgs.resolve(tokens,names); if(all!=null) { slug=all; typed=tokens; hand=true; } }
   if(slug==null) { p.sendMessage("Kurs nicht gefunden: "+String.join(" ",typed)); return true; }
   Material material;
   if(hand) material=p.getInventory().getItemInMainHand().getType();
   else { String icon=ParkourSql.icon(tokens.get(tokens.size()-1)); material=icon==null?null:materialLookup.apply(icon); }
   MaterialIcon resolved=materialIcon.apply(material);
   if(!resolved.valid()) { p.sendMessage("Ungueltiges Material oder kein Item in der Hand."); return true; }
   value=resolved.value(); if(value==null) { p.sendMessage("Ungueltiges Material."); return true; }
  }
  try { if(plugin.updateCourse(slug,rename?"display_name":"icon",value)) p.sendMessage("Kurs "+slug+" aktualisiert: "+value); else p.sendMessage("Kurs nicht gefunden: "+String.join(" ",typed)); }
  catch(java.sql.SQLException ex) { plugin.getLogger().log(java.util.logging.Level.WARNING,"Kurs konnte nicht gespeichert werden",ex); p.sendMessage("Kurs konnte nicht gespeichert werden."); }
  return true;
 }
 @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] a) {
  if(!sender.hasPermission("craftcore.holo.admin")) return List.of();
  if(a.length>=3 && a[0].equalsIgnoreCase("parkour") && (a[1].equalsIgnoreCase("rename") || a[1].equalsIgnoreCase("icon"))) {
   List<String> tokens=Arrays.asList(a).subList(2,a.length); Map<String,String> names=plugin.courseNames();
   List<String> choices=new ArrayList<>(CourseArgs.complete(tokens,names));
   if(a[1].equalsIgnoreCase("icon") && CourseArgs.resolve(tokens.subList(0,tokens.size()-1),names)!=null)
    Arrays.stream(Material.values()).filter(m->!m.isLegacy() && m.isItem() && !m.isAir()).map(m->m.getKey().getKey()).filter(x->ParkourSql.icon(x)!=null && x.startsWith(tokens.get(tokens.size()-1).toLowerCase(Locale.ROOT))).sorted().forEach(choices::add);
   return choices;
  }
  List<String> choices=a.length==1?List.of("holo","parkour"):a[0].equalsIgnoreCase("parkour") && a.length==2?List.of("rename","icon"):a.length==2 && a[0].equalsIgnoreCase("holo")?List.of("list","remove","move"):a.length==3 && a[0].equalsIgnoreCase("holo") && (a[1].equalsIgnoreCase("remove")||a[1].equalsIgnoreCase("move"))?boards().stream().map(Board::id).toList():List.of();
  return choices.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(a[a.length-1].toLowerCase(Locale.ROOT))).toList();
 }
}
