package de.mcstack.craftcore.pvp;

import java.io.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class PvpModule implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final PvpStore.ConnectionSource connectionSource;
    private PvpMatches matches;
    private World build;
    private final File arenas, kitsFile;
    private YamlConfiguration kits;
    private final Map<UUID,Location[]> selections=new HashMap<>();
    private final Map<String,Draft> drafts=new TreeMap<>();
    private static class Draft { PvpLogic.Region region; final Map<Integer,Location> spawns=new TreeMap<>(); }
    public PvpModule(JavaPlugin plugin, PvpStore.ConnectionSource connectionSource) { this.plugin=plugin; this.connectionSource=connectionSource; File root=new File(plugin.getDataFolder(),"pvp"); arenas=new File(root,"arenas"); kitsFile=new File(root,"kits.yml"); }
    public void start() {
        if(!arenas.mkdirs() && !arenas.isDirectory()) throw new IllegalStateException("Arena-Verzeichnis nicht verfügbar");
        kits=YamlConfiguration.loadConfiguration(kitsFile);
        build=Bukkit.createWorld(new WorldCreator("pvp_build").generator(new ChunkGenerator() {}));
        if(build==null) throw new IllegalStateException("pvp_build konnte nicht geladen werden");
        PluginCommand cmd=Objects.requireNonNull(plugin.getCommand("pvp")); cmd.setExecutor(this); cmd.setTabCompleter(this);
        PvpStore store=new PvpStore(connectionSource);
        Objects.requireNonNull(plugin.getCommand("pvpdebug")).setExecutor(new PvpDebug(plugin,store));
        matches=new PvpMatches(plugin,store,arenas,kitsFile); matches.start();
    }
    private static PvpLogic.Point point(Location l) { return new PvpLogic.Point(l.getBlockX(),l.getBlockY(),l.getBlockZ()); }
    private boolean inBuild(Player p) { if(!p.getWorld().equals(build)) {p.sendMessage("Nur in pvp_build verfügbar.");return false;} return true; }
    private File file(String name) { return new File(arenas,name+".yml"); }
    private Draft draft(String name) {
        Draft existing=drafts.get(name); if(existing!=null) return existing;
        if(!file(name).isFile()) return null;
        YamlConfiguration y=YamlConfiguration.loadConfiguration(file(name)); PvpLogic.Arena a=PvpLogic.Arena.read(y); Draft d=new Draft(); d.region=a.region();
        a.spawns().forEach((n,s)->d.spawns.put(n,new Location(build,s.x()+d.region.min().x(),s.y()+d.region.min().y(),s.z()+d.region.min().z(),s.yaw(),s.pitch()))); drafts.put(name,d); return d;
    }
    private void save(String name,Draft d) throws IOException {
        if(d.region==null || !d.spawns.containsKey(1) || !d.spawns.containsKey(2) || !d.spawns.values().stream().allMatch(s->s.getWorld().equals(build) && d.region.contains(point(s)))) throw new IllegalArgumentException("Region und gültige Spawns 1 und 2 erforderlich.");
        Map<Integer,PvpLogic.Spawn> spawns=new TreeMap<>(); d.spawns.forEach((n,l)->spawns.put(n,new PvpLogic.Spawn(l.getX()-d.region.min().x(),l.getY()-d.region.min().y(),l.getZ()-d.region.min().z(),l.getYaw(),l.getPitch())));
        YamlConfiguration y=new YamlConfiguration(); new PvpLogic.Arena(d.region,spawns).write(y);
        List<String> blocks=new ArrayList<>(); PvpLogic.Point min=d.region.min(), max=d.region.max();
        for(int x=min.x();x<=max.x();x++) for(int z=min.z();z<=max.z();z++) for(int yy=min.y();yy<=max.y();yy++) { Block b=build.getBlockAt(x,yy,z); blocks.add(b.getBlockData().getAsString()); }
        y.set("blocks",blocks); y.save(file(name));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a) {
        if(!(sender instanceof Player p)) { sender.sendMessage("Nur für Spieler.");return true; }
        if(a.length==1 && a[0].equalsIgnoreCase("leave")) { p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); p.setGameMode(GameMode.ADVENTURE); return true; }
        if(!PvpAccess.mayBypass(p::hasPermission)) {p.sendMessage("Keine Berechtigung.");return true;}
        try {
            if(a.length==1 && a[0].equalsIgnoreCase("build")) { p.teleport(build.getSpawnLocation());p.setGameMode(GameMode.CREATIVE);return true; }
            if(a.length>=2 && a[0].equalsIgnoreCase("arena")) {
                String op=a[1].toLowerCase(Locale.ROOT);
                if(op.equals("list") && a.length==2) {p.sendMessage("Arenen: "+String.join(", ",names()));return true;}
                if((op.equals("pos1") || op.equals("pos2")) && a.length==2 && inBuild(p)) {selections.computeIfAbsent(p.getUniqueId(),k->new Location[2])[op.equals("pos1")?0:1]=p.getLocation().clone();p.sendMessage("Position gesetzt.");return true;}
                if(a.length<3 || !PvpLogic.validName(a[2])) throw new IllegalArgumentException("Name: 1-32 Zeichen a-z, 0-9, _ oder -.");
                String name=a[2];
                if(op.equals("create") && a.length==3) {if(drafts.containsKey(name)||file(name).exists()) throw new IllegalArgumentException("Arena existiert bereits.");drafts.put(name,new Draft());}
                else if(op.equals("remove") && a.length==3) {boolean existed=drafts.remove(name)!=null; File f=file(name); if(f.exists()) {if(!f.delete()) throw new IOException("Löschen fehlgeschlagen");existed=true;} if(!existed) throw new IllegalArgumentException("Arena nicht gefunden.");}
                else { Draft d=draft(name); if(d==null) throw new IllegalArgumentException("Arena nicht gefunden.");
                    if(op.equals("region") && a.length==3 && inBuild(p)) {Location[] sel=selections.get(p.getUniqueId());if(sel==null||sel[0]==null||sel[1]==null) throw new IllegalArgumentException("Pos1 und Pos2 fehlen.");d.region=PvpLogic.Region.between(point(sel[0]),point(sel[1]));}
                    else if(op.equals("setspawn") && a.length==4 && inBuild(p)) {int n=Integer.parseInt(a[3]);if(n<1) throw new IllegalArgumentException("Spawn muss >= 1 sein.");d.spawns.put(n,p.getLocation().clone());}
                    else if(op.equals("save") && a.length==3 && inBuild(p)) save(name,d);
                    else throw new IllegalArgumentException("Ungültiger Arena-Befehl.");
                } p.sendMessage("Arena aktualisiert: "+name);return true;
            }
            if(a.length>=2 && a[0].equalsIgnoreCase("kit")) {
                String op=a[1].toLowerCase(Locale.ROOT);
                if(op.equals("list") && a.length==2) {p.sendMessage("Kits: "+String.join(", ",kitNames()));return true;}
                if(a.length!=3 || !PvpLogic.validName(a[2])) throw new IllegalArgumentException("Ungültiger Kit-Name."); String key="kits."+a[2];
                if(op.equals("create")) {if(kits.contains(key)) throw new IllegalArgumentException("Kit existiert bereits.");kits.set(key+".contents",Arrays.asList(p.getInventory().getStorageContents()));kits.set(key+".armor",Arrays.asList(p.getInventory().getArmorContents()));kits.set(key+".offhand",p.getInventory().getItemInOffHand());}
                else if(op.equals("icon")) {if(!kits.contains(key)) throw new IllegalArgumentException("Kit nicht gefunden.");ItemStack item=p.getInventory().getItemInMainHand();if(item.getType().isAir()) throw new IllegalArgumentException("Item in der Hand fehlt.");kits.set(key+".icon",item.clone());}
                else if(op.equals("remove")) {if(!kits.contains(key)) throw new IllegalArgumentException("Kit nicht gefunden.");kits.set(key,null);}
                else throw new IllegalArgumentException("Ungültiger Kit-Befehl."); kits.save(kitsFile);matches.syncKits();p.sendMessage("Kit aktualisiert: "+a[2]);return true;
            }
            p.sendMessage("/pvp build|leave|arena|kit");
        } catch(IllegalArgumentException | IOException e) {p.sendMessage("PvP: "+e.getMessage());} return true;
    }
    private List<String> names() {Set<String> n=new TreeSet<>(drafts.keySet());String[] files=arenas.list((dir,name)->name.endsWith(".yml"));if(files!=null) for(String f:files)n.add(f.substring(0,f.length()-4));return new ArrayList<>(n);}
    private List<String> kitNames() {var s=kits.getConfigurationSection("kits");return s==null?List.of():new ArrayList<>(s.getKeys(false));}
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] a) {
        List<String> opts=List.of(); boolean allowed=PvpAccess.mayBypass(sender::hasPermission);
        if(a.length==1) opts=allowed?List.of("build","leave","arena","kit"):List.of("leave");
        else if(allowed && a.length==2 && a[0].equalsIgnoreCase("arena")) opts=List.of("create","pos1","pos2","region","setspawn","save","list","remove");
        else if(allowed && a.length==2 && a[0].equalsIgnoreCase("kit")) opts=List.of("create","icon","list","remove");
        else if(allowed && a.length==3 && a[0].equalsIgnoreCase("arena") && !a[1].equalsIgnoreCase("create")) opts=names();
        else if(allowed && a.length==3 && a[0].equalsIgnoreCase("kit") && !a[1].equalsIgnoreCase("create")) opts=kitNames();
        else if(allowed && a.length==4 && a[0].equalsIgnoreCase("arena") && a[1].equalsIgnoreCase("setspawn")) opts=List.of("1","2");
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);return opts.stream().filter(s->s.startsWith(prefix)).toList();
    }
}
