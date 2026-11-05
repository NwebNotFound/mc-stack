package de.mcstack.craftcore;

import java.io.File;
import java.io.IOException;
import java.util.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.util.Vector;

final class CitizensNpcs implements Listener {
  private final CraftCore core;
  private final UserMenu menu;
  private final File file;
  private final YamlConfiguration config;
  private final Map<String,NpcDefinitions.NpcDef> definitions=new LinkedHashMap<>();
  private final Map<String,NPC> npcs=new HashMap<>();
  private final NpcLifecycle<NPC> lifecycle=new NpcLifecycle<>();
  private NPCRegistry registry;
  private boolean active;
  CitizensNpcs(CraftCore core, UserMenu menu) { this.core=core; this.menu=menu; file=new File(core.getDataFolder(),"npcs.yml"); config=YamlConfiguration.loadConfiguration(file); }
  void start() {
    ConfigurationSection section=config.getConfigurationSection("npcs");
    if(section!=null) for(String key:section.getKeys(false)) try {
      ConfigurationSection entry=section.getConfigurationSection(key);
      if(entry==null) continue;
      definitions.put(key,NpcDefinitions.load(key,entry.getValues(false)));
    } catch(RuntimeException ex) { core.getLogger().warning("NPC " + key + ": " + ex.getMessage()); }
    Bukkit.getPluginManager().registerEvents(this,core);
    Bukkit.getScheduler().runTaskLater(core,this::activate,200L);
    Bukkit.getScheduler().runTaskTimer(core,this::actionbars,5L,5L);
  }
  void activate() {
    if(lifecycle.isActive() || !Bukkit.getPluginManager().isPluginEnabled("Citizens")) return;
    registry=CitizensAPI.getNamedNPCRegistry("craftcore");
    if(registry==null) registry=CitizensAPI.createNamedNPCRegistry("craftcore",new MemoryNPCDataStore());
    List<NpcLifecycle.LegacyId> legacy=new ArrayList<>();
    for(var def:definitions.values()) {
      String path="npcs."+def.key();
      legacy.add(new NpcLifecycle.LegacyId(config.contains(path+".citizensId") ? config.getInt(path+".citizensId") : null,config.getString(path+".citizensUuid")));
    }
    var result=lifecycle.activate(new NpcLifecycle.Registry<>() {
      public List<NPC> owned() { return snapshot(registry); }
      public List<NPC> defaults() { return snapshot(CitizensAPI.getNPCRegistry()); }
      public int id(NPC npc) { return npc.getId(); }
      public String uuid(NPC npc) { return npc.getUniqueId().toString(); }
      public boolean tagged(NPC npc) { return npc.data().has("craftcore-npc"); }
      public void remove(NPC npc) { npc.destroy(); }
      public void create(String key) { apply(definitions.get(key)); }
    },definitions.keySet(),legacy);
    active=true;
    save();
    core.getLogger().info("NPCs: removed " + result.removed() + ", spawned " + result.spawned());
  }
  private static List<NPC> snapshot(NPCRegistry registry) {
    List<NPC> result=new ArrayList<>();
    for(NPC npc:registry) result.add(npc);
    return result;
  }
  @EventHandler public void citizensReady(net.citizensnpcs.api.event.CitizensEnableEvent e) { activate(); }
  @EventHandler public void disable(PluginDisableEvent e) { if(e.getPlugin().getName().equalsIgnoreCase("Citizens")) { active=false; lifecycle.reset(); npcs.clear(); registry=null; } }
  private void apply(NpcDefinitions.NpcDef def) {
    String path="npcs."+def.key();
    NPC npc=npcs.get(def.key());
    if(npc==null) npc=registry.createNPC(EntityType.PLAYER, color(def.displayName()));
    npcs.put(def.key(),npc);
    npc.data().set("craftcore-npc",def.key());
    npc.setName(color(def.displayName()));
    npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);
    if(!def.skin().isBlank()) npc.getOrAddTrait(SkinTrait.class).setSkinName(def.skin(),true);
    LookClose look=npc.getOrAddTrait(LookClose.class); look.lookClose(true); look.setRange(8); look.setRealisticLooking(true);
    Location location=config.getLocation(path+".location");
    if(!npc.isSpawned() && location!=null && location.getWorld()!=null) npc.spawn(location);
  }
  private static String color(String value) { return ChatColor.translateAlternateColorCodes('&',value); }
  private void save() {
    for(var def:definitions.values()) {
      String path="npcs."+def.key();
      for(var entry:NpcDefinitions.save(def).entrySet()) config.set(path+"."+entry.getKey(),entry.getValue());
      config.set(path+".citizensId",null); config.set(path+".citizensUuid",null);
    }
    try { core.getDataFolder().mkdirs(); config.save(file); } catch(IOException ex) { core.getLogger().severe("NPC-Datei konnte nicht gespeichert werden: "+ex.getMessage()); }
  }
  private void actionbars() {
    if(!active) return;
    for(Player p:Bukkit.getOnlinePlayers()) {
      Location eye=p.getEyeLocation(); Vector direction=eye.getDirection(); NpcDefinitions.NpcDef closest=null; double best=Double.POSITIVE_INFINITY;
      for(var def:definitions.values()) {
        NPC npc=npcs.get(def.key()); if(npc==null || !npc.isSpawned() || def.message().isEmpty()) continue;
        Location pos=npc.getEntity().getLocation().add(0,1.0,0); if(!pos.getWorld().equals(eye.getWorld())) continue;
        double distance=eye.distanceSquared(pos);
        if(distance<best && NpcDefinitions.inViewAndRange(eye.getX(),eye.getY(),eye.getZ(),direction.getX(),direction.getY(),direction.getZ(),pos.getX(),pos.getY(),pos.getZ())) { closest=def; best=distance; }
      }
      if(closest!=null) p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(closest.message()));
    }
  }
  @EventHandler public void right(NPCRightClickEvent e) { interact(e.getNPC(),e.getClicker()); }
  @EventHandler public void left(NPCLeftClickEvent e) { interact(e.getNPC(),e.getClicker()); }
  private void interact(NPC npc,Player player) {
    if(!active) return;
    for(var entry:npcs.entrySet()) if(entry.getValue().equals(npc)) {
      var def=definitions.get(entry.getKey());
      switch(def.action()) {
        case SERVER -> ProxyChannel.sendOrTell(core, player, def.actionArg());
        case MENU -> {
          var lobbyPvp=core.lobbyPvp();
          switch(NpcDefinitions.menuAction(def.actionArg(),lobbyPvp!=null)) {
            case PVP -> lobbyPvp.openMenu(player);
            case PVP_UNAVAILABLE -> { player.sendMessage("PvP-Menue ist gerade nicht verfuegbar."); core.getLogger().warning("PvP-Menue fuer NPC " + def.key() + " ist nicht verfuegbar."); }
            case COMMAND -> player.performCommand(def.actionArg());
            case MENU -> menu.open(player,def.actionArg().toLowerCase(Locale.ROOT));
          }
        }
        case COMMAND -> player.performCommand(def.actionArg().replace("{player}",player.getName()));
        case NONE -> { }
      }
      return;
    }
  }
  void command(CommandSender sender,String[] args) {
    if(!sender.hasPermission("craftcore.admin.npc")) { sender.sendMessage("Keine Berechtigung."); return; }
    if(!active) { sender.sendMessage("Citizens ist nicht aktiv."); return; }
    if(args.length==0) { sender.sendMessage("/cc npc create|rename|setskin|setmessage|setaction|move|remove|list"); return; }
    String op=args[0].toLowerCase(Locale.ROOT);
    if(op.equals("list")) { definitions.values().forEach(d->sender.sendMessage(color(d.displayName())+" - "+d.action().name().toLowerCase(Locale.ROOT)+(d.actionArg().isEmpty()?"":" "+d.actionArg()))); return; }
    try {
      if(args.length<2) throw new IllegalArgumentException("Name fehlt");
      if(op.equals("create")) {
        if(!(sender instanceof Player player)) throw new IllegalArgumentException("Nur im Spiel");
        String name=String.join(" ",Arrays.copyOfRange(args,1,args.length)), key=NpcDefinitions.keyFromName(name);
        if(definitions.containsKey(key) || NpcDefinitions.find(definitions.values(),name).isPresent()) throw new IllegalArgumentException("NPC existiert bereits");
        var def=new NpcDefinitions.NpcDef(key,name,"","",NpcDefinitions.ActionType.NONE,"");
        definitions.put(key,def); config.set("npcs."+key+".location",player.getLocation()); apply(def); save(); sender.sendMessage("NPC erstellt."); return;
      }
      NpcDefinitions.NpcDef def=null; int nameEnd=1;
      int limit=(op.equals("move") || op.equals("remove")) ? args.length : args.length-1;
      for(int end=limit;end>=2;end--) { var match=NpcDefinitions.find(definitions.values(),String.join(" ",Arrays.copyOfRange(args,1,end))); if(match.isPresent()) { def=match.get(); nameEnd=end; break; } }
      if(def==null) throw new IllegalArgumentException("NPC nicht gefunden");
      String path="npcs."+def.key();
      if(op.equals("remove")) { if(args.length!=nameEnd) throw new IllegalArgumentException("Ungueltige Argumente"); NPC npc=npcs.remove(def.key()); if(npc!=null) npc.destroy(); definitions.remove(def.key()); config.set(path,null); save(); sender.sendMessage("NPC entfernt."); return; }
      if(op.equals("move")) { if(args.length!=nameEnd || !(sender instanceof Player player)) throw new IllegalArgumentException("Nur im Spiel: /cc npc move <name>"); config.set(path+".location",player.getLocation()); NPC npc=npcs.get(def.key()); if(npc.isSpawned()) npc.teleport(player.getLocation(),org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); else npc.spawn(player.getLocation()); save(); sender.sendMessage("NPC verschoben."); return; }
      if(args.length<=nameEnd) throw new IllegalArgumentException("Wert fehlt");
      String value=String.join(" ",Arrays.copyOfRange(args,nameEnd,args.length));
      switch(op) {
        case "rename" -> { NpcDefinitions.validateDisplayName(definitions.values(),def.key(),value); def=new NpcDefinitions.NpcDef(def.key(),value,def.skin(),def.message(),def.action(),def.actionArg()); }
        case "setskin" -> { if(args.length!=nameEnd+1) throw new IllegalArgumentException("Ein Skinname erwartet"); def=new NpcDefinitions.NpcDef(def.key(),def.displayName(),value,def.message(),def.action(),def.actionArg()); }
        case "setmessage" -> def=new NpcDefinitions.NpcDef(def.key(),def.displayName(),def.skin(),value,def.action(),def.actionArg());
        case "setaction" -> { var action=NpcDefinitions.parseAction(value); if(action.type()==NpcDefinitions.ActionType.MENU && !Set.of("menu","bank","shop","ah","stats","pay","trade","team","claim","home","vip","pvp").contains(action.arg().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Unbekanntes Menue"); def=new NpcDefinitions.NpcDef(def.key(),def.displayName(),def.skin(),def.message(),action.type(),action.arg()); }
        default -> throw new IllegalArgumentException("Unbekannter Unterbefehl");
      }
      definitions.put(def.key(),def); apply(def); save(); sender.sendMessage("NPC aktualisiert.");
    } catch(IllegalArgumentException ex) { sender.sendMessage(ex.getMessage()); }
  }
  List<String> complete(CommandSender sender,String[] args) {
    if(!sender.hasPermission("craftcore.admin.npc")) return List.of();
    if(args.length==1) return UserMenu.complete(args[0],"create","rename","setskin","setmessage","setaction","move","remove","list");
    if(args.length==2 && Set.of("rename","setskin","setmessage","setaction","move","remove").contains(args[0].toLowerCase(Locale.ROOT))) return definitions.keySet().stream().filter(k->k.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
    if(args.length==3 && args[0].equalsIgnoreCase("setaction")) return UserMenu.complete(args[2],"server","menu","command","none");
    return List.of();
  }
}
