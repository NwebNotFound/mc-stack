package de.mcstack.craftcore.pvp;

import java.io.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
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
    private final File arenas, draftsDir, kitsFile;
    private ArenaEditor editor;
    private YamlConfiguration kits;
    public PvpModule(JavaPlugin plugin, PvpStore.ConnectionSource connectionSource) { this.plugin=plugin; this.connectionSource=connectionSource; File root=new File(plugin.getDataFolder(),"pvp"); arenas=new File(root,"arenas"); draftsDir=new File(root,"drafts"); kitsFile=new File(root,"kits.yml"); }
    public void start() {
        if(!arenas.mkdirs() && !arenas.isDirectory()) throw new IllegalStateException("Arena-Verzeichnis nicht verfuegbar");
        if(!draftsDir.mkdirs() && !draftsDir.isDirectory()) throw new IllegalStateException("Entwurfsverzeichnis nicht verfügbar");
        kits=YamlConfiguration.loadConfiguration(kitsFile);
        build=Bukkit.createWorld(new WorldCreator("pvp_build").generator(new ChunkGenerator() {}));
        if(build==null) throw new IllegalStateException("pvp_build konnte nicht geladen werden");
        editor=new ArenaEditor(new EditorStorage(arenas,draftsDir,build.getName(),(x,y,z)->build.getBlockAt(x,y,z).getBlockData().getAsString()),build.getName());
        PluginCommand cmd=Objects.requireNonNull(plugin.getCommand("pvp")); cmd.setExecutor(this); cmd.setTabCompleter(this);
        PvpStore store=new PvpStore(connectionSource);
        Objects.requireNonNull(plugin.getCommand("pvpdebug")).setExecutor(new PvpDebug(plugin,store));
        matches=new PvpMatches(plugin,store,arenas,kitsFile,editor,build); matches.start();
    }
    private static ArenaEditor.Position position(Location l) { return new ArenaEditor.Position(l.getWorld().getName(),l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch()); }
    private PvpLogic.Region worldEditSelection(Player player) {
        var pluginInstance=Bukkit.getPluginManager().getPlugin("WorldEdit");
        if(pluginInstance==null || !pluginInstance.isEnabled()) return null;
        try {
            ClassLoader loader=pluginInstance.getClass().getClassLoader();
            Class<?> adapter=Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter",true,loader);
            Class<?> actor=Class.forName("com.sk89q.worldedit.extension.platform.Actor",true,loader);
            Class<?> weWorld=Class.forName("com.sk89q.worldedit.world.World",true,loader);
            Object wePlayer=adapter.getMethod("adapt",Player.class).invoke(null,player);
            Object world=adapter.getMethod("adapt",World.class).invoke(null,build);
            Class<?> we=Class.forName("com.sk89q.worldedit.WorldEdit",true,loader);
            Object instance=we.getMethod("getInstance").invoke(null);
            Object manager=we.getMethod("getSessionManager").invoke(instance);
            Object session=manager.getClass().getMethod("get",actor).invoke(manager,wePlayer);
            Object region=session.getClass().getMethod("getSelection",weWorld).invoke(session,world);
            Class<?> regionClass=Class.forName("com.sk89q.worldedit.regions.Region",true,loader);
            return PvpLogic.Region.between(vector(regionClass.getMethod("getMinimumPoint").invoke(region)),vector(regionClass.getMethod("getMaximumPoint").invoke(region)));
        } catch (ReflectiveOperationException | LinkageError e) { return null; }
    }
    private static PvpLogic.Point vector(Object v) throws ReflectiveOperationException {
        Class<?> c=v.getClass(); return new PvpLogic.Point((int)c.getMethod("getBlockX").invoke(v),(int)c.getMethod("getBlockY").invoke(v),(int)c.getMethod("getBlockZ").invoke(v));
    }
    @FunctionalInterface interface BlockSource { String get(int x, int y, int z); }
    static final class EditorStorage implements ArenaEditor.Storage {
        private final File arenas, draftsDir;
        private final BlockSource blocks;
        private final String buildWorld;
        EditorStorage(File arenas, File draftsDir, String buildWorld, BlockSource blocks) { this.arenas=arenas; this.draftsDir=draftsDir; this.buildWorld=buildWorld; this.blocks=blocks; }
        private File file(String name) { return new File(arenas,name+".yml"); }
        public Set<String> savedNames() { Set<String> names=new TreeSet<>(); String[] files=arenas.list((dir,n)->n.endsWith(".yml")); if(files!=null) for(String f:files) names.add(f.substring(0,f.length()-4)); return names; }
        public Map<String,ArenaEditor.Draft> loadDrafts() {
            Map<String,ArenaEditor.Draft> result=new TreeMap<>(); File[] files=draftsDir.listFiles((dir,n)->n.endsWith(".yml"));
            if(files!=null) for(File f:files) { String name=f.getName().substring(0,f.getName().length()-4); if(PvpLogic.validName(name)) result.put(name,readDraft(YamlConfiguration.loadConfiguration(f))); } return result;
        }
        private ArenaEditor.Draft readDraft(YamlConfiguration y) {
            ArenaEditor.Draft d=new ArenaEditor.Draft();
            if(y.contains("min.x") && y.contains("max.x")) d.region=PvpLogic.Region.between(new PvpLogic.Point(y.getInt("min.x"),y.getInt("min.y"),y.getInt("min.z")),new PvpLogic.Point(y.getInt("max.x"),y.getInt("max.y"),y.getInt("max.z")));
            var section=y.getConfigurationSection("spawns"); if(section!=null) for(String key:section.getKeys(false)) { try { int n=Integer.parseInt(key); String k="spawns."+key+"."; d.spawns.put(n,new ArenaEditor.Position(y.getString(k+"world",buildWorld),y.getDouble(k+"x"),y.getDouble(k+"y"),y.getDouble(k+"z"),(float)y.getDouble(k+"yaw"),(float)y.getDouble(k+"pitch"))); } catch(NumberFormatException ignored) {} } return d;
        }
        public ArenaEditor.Draft loadSaved(String name) {
            PvpLogic.Arena a=PvpLogic.Arena.read(YamlConfiguration.loadConfiguration(file(name))); ArenaEditor.Draft d=new ArenaEditor.Draft(); d.region=a.region();
            a.spawns().forEach((n,s)->d.spawns.put(n,new ArenaEditor.Position(buildWorld,s.x()+d.region.min().x(),s.y()+d.region.min().y(),s.z()+d.region.min().z(),s.yaw(),s.pitch()))); return d;
        }
        public void writeDraft(String name,ArenaEditor.Draft d) throws IOException {
            YamlConfiguration y=new YamlConfiguration(); if(d.region!=null) { var min=d.region.min(); var max=d.region.max(); y.set("min.x",min.x()); y.set("min.y",min.y()); y.set("min.z",min.z()); y.set("max.x",max.x()); y.set("max.y",max.y()); y.set("max.z",max.z()); }
            d.spawns.forEach((n,p)->{String k="spawns."+n+"."; y.set(k+"world",p.world()); y.set(k+"x",p.x()); y.set(k+"y",p.y()); y.set(k+"z",p.z()); y.set(k+"yaw",p.yaw()); y.set(k+"pitch",p.pitch());}); y.save(new File(draftsDir,name+".yml"));
        }
        public void deleteDraft(String name) throws IOException { File f=new File(draftsDir,name+".yml"); if(f.exists() && !f.delete()) throw new IOException("Entwurf konnte nicht gelöscht werden."); }
        public void saveArena(String name,ArenaEditor.Draft d) throws IOException {
            Map<Integer,PvpLogic.Spawn> spawns=new TreeMap<>(); d.spawns.forEach((n,p)->spawns.put(n,new PvpLogic.Spawn(p.x()-d.region.min().x(),p.y()-d.region.min().y(),p.z()-d.region.min().z(),p.yaw(),p.pitch())));
            YamlConfiguration y=new YamlConfiguration(); new PvpLogic.Arena(d.region,spawns).write(y); List<String> blockData=new ArrayList<>(); var min=d.region.min(); var max=d.region.max();
            for(int x=min.x();x<=max.x();x++) for(int z=min.z();z<=max.z();z++) for(int yy=min.y();yy<=max.y();yy++) { blockData.add(blocks.get(x,yy,z)); } y.set("blocks",blockData); y.save(file(name));
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a) {
        if(!(sender instanceof Player p)) { sender.sendMessage("Nur fuer Spieler.");return true; }
        if(a.length==1 && a[0].equalsIgnoreCase("leave") && !PvpAccess.mayLeave(matches!=null && matches.inMatch(p.getUniqueId()))) { p.sendMessage("Waehrend eines PvP-Matches nicht moeglich."); return true; }
        if(a.length==1 && a[0].equalsIgnoreCase("leave")) { p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); p.setGameMode(GameMode.ADVENTURE); return true; }
        if(!PvpAccess.mayBypass(p::hasPermission)) {p.sendMessage("Keine Berechtigung.");return true;}
        try {
            if(a.length==1 && a[0].equalsIgnoreCase("build")) { p.teleport(build.getSpawnLocation());p.setGameMode(GameMode.CREATIVE);return true; }
            if(a.length>=1 && a[0].equalsIgnoreCase("arena")) {
                ArenaEditor.Context context=new ArenaEditor.Context() {
                    public String id() { return p.getUniqueId().toString(); }
                    public ArenaEditor.Position position() { return PvpModule.position(p.getLocation()); }
                    public PvpLogic.Region worldEditSelection() { return PvpModule.this.worldEditSelection(p); }
                };
                p.sendMessage(editor.execute(context,Arrays.copyOfRange(a,1,a.length))); return true;
            }
            if(a.length>=2 && a[0].equalsIgnoreCase("kit")) {
                String op=a[1].toLowerCase(Locale.ROOT);
                if(op.equals("list") && a.length==2) {p.sendMessage("Kits: "+String.join(", ",kitNames()));return true;}
                if(a.length!=3 || !PvpLogic.validName(a[2])) throw new IllegalArgumentException("Ungueltiger Kit-Name."); String key="kits."+a[2];
                if(op.equals("create")) {if(kits.contains(key)) throw new IllegalArgumentException("Kit existiert bereits.");kits.set(key+".contents",Arrays.asList(p.getInventory().getStorageContents()));kits.set(key+".armor",Arrays.asList(p.getInventory().getArmorContents()));kits.set(key+".offhand",p.getInventory().getItemInOffHand());}
                else if(op.equals("icon")) {if(!kits.contains(key)) throw new IllegalArgumentException("Kit nicht gefunden.");ItemStack item=p.getInventory().getItemInMainHand();if(item.getType().isAir()) throw new IllegalArgumentException("Item in der Hand fehlt.");kits.set(key+".icon",item.clone());}
                else if(op.equals("remove")) {if(!kits.contains(key)) throw new IllegalArgumentException("Kit nicht gefunden.");kits.set(key,null);}
                else throw new IllegalArgumentException("Ungueltiger Kit-Befehl."); kits.save(kitsFile);matches.syncKits();p.sendMessage("Kit aktualisiert: "+a[2]);return true;
            }
            p.sendMessage("/pvp build|leave|arena|kit");
        } catch(ArenaEditor.BuildWorldException e) {p.sendMessage(buildWorldMessage(e.getMessage()));}
        catch(IllegalArgumentException | IOException e) {p.sendMessage("PvP: "+e.getMessage());} return true;
    }
    static Component buildWorldMessage(String message) {
        String link="Nutze /pvp build"; int start=message.lastIndexOf(link);
        return Component.text("PvP: "+message.substring(0,start)).append(Component.text(link).clickEvent(ClickEvent.runCommand("/pvp build"))).append(Component.text(message.substring(start+link.length())));
    }
    private List<String> kitNames() {var s=kits.getConfigurationSection("kits");return s==null?List.of():new ArrayList<>(s.getKeys(false));}
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] a) {
        List<String> opts=List.of(); boolean allowed=PvpAccess.mayBypass(sender::hasPermission);
        if(a.length==1) opts=allowed?List.of("build","leave","arena","kit"):List.of("leave");
        else if(allowed && a.length==2 && a[0].equalsIgnoreCase("arena")) opts=List.of("create","pos1","pos2","region","setspawn","save","list","remove","info");
        else if(allowed && a.length==2 && a[0].equalsIgnoreCase("kit")) opts=List.of("create","icon","list","remove");
        else if(allowed && a.length==3 && a[0].equalsIgnoreCase("arena") && List.of("region","setspawn","save","remove","info","pos1","pos2").contains(a[1].toLowerCase(Locale.ROOT))) opts=editor.names();
        else if(allowed && a.length==3 && a[0].equalsIgnoreCase("kit") && !a[1].equalsIgnoreCase("create")) opts=kitNames();
        else if(allowed && a.length==4 && a[0].equalsIgnoreCase("arena") && a[1].equalsIgnoreCase("setspawn")) opts=List.of("1","2");
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);return opts.stream().filter(s->s.startsWith(prefix)).toList();
    }
}
