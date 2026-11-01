package de.mcstack.craftcore.pvp;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class PvpMatches implements Listener {
    private final JavaPlugin plugin;
    private final PvpStore store;
    private final File arenas, kitsFile;
    private final Map<String, Session> sessions=new HashMap<>();
    private final Map<UUID, Session> players=new HashMap<>();
    private final Set<String> starting=new HashSet<>();
    private final ArenaEditor editor;
    private final World build;
    private long lastEditorIdleLog;
    private final IdleStop idle=new IdleStop(IdleStop.DEFAULT_GRACE_MS);
    private final AtomicBoolean counting=new AtomicBoolean();
    private record PendingCheck(Boolean pending, long checkedAt) {}
    private volatile PendingCheck pendingCheck=new PendingCheck(null,0);
    private final Object kitLock=new Object();
    private volatile long kitVersion;
    private static final class Session {
        final PvpStore.Assignment assignment; final long waitingSince;
        PvpMatch match; World world; PvpLogic.Arena arena; String arenaName; boolean active, finishing, claimed;
        Session(PvpStore.Assignment a) { assignment=a; waitingSince=System.currentTimeMillis(); }
    }
    public PvpMatches(JavaPlugin plugin,PvpStore store,File arenas,File kitsFile,ArenaEditor editor,World build) { this.plugin=plugin;this.store=store;this.arenas=arenas;this.kitsFile=kitsFile;this.editor=editor;this.build=build; }
    public void start() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,"BungeeCord");
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
        Bukkit.getScheduler().runTaskTimer(plugin,this::idleTick,0L,100L);
        idle.tick(System.currentTimeMillis(), !Bukkit.getOnlinePlayers().isEmpty());
        syncKits();
    }
    public void syncKits() {
        YamlConfiguration y=YamlConfiguration.loadConfiguration(kitsFile); Map<String,String> icons=new HashMap<>();
        var section=y.getConfigurationSection("kits");
        if(section!=null) for(String name:section.getKeys(false)) { ItemStack icon=y.getItemStack("kits."+name+".icon"); icons.put(name,icon==null?"IRON_SWORD":icon.getType().name()); }
        long version=++kitVersion;
        async(() -> { synchronized(kitLock) { if(version==kitVersion) store.syncKits(icons); } });
    }
    @FunctionalInterface private interface DbTask { void run() throws SQLException; }
    private void async(DbTask task) { Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{ try { task.run(); } catch(Exception e) { plugin.getLogger().warning("PvP-Datenbank: "+e.getMessage()); } }); }
    private void main(Runnable task) { Bukkit.getScheduler().runTask(plugin,task); }
    @EventHandler public void join(PlayerJoinEvent e) {
        idle.tick(System.currentTimeMillis(), true);
        Player p=e.getPlayer(); reset(p); p.setGameMode(GameMode.ADVENTURE); p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        boolean builder=PvpAccess.mayBypass(p::hasPermission);
        UUID id=p.getUniqueId();
        async(()->{ Optional<PvpStore.Assignment> found; try { found=store.pendingFor(id); } catch(SQLException | RuntimeException ex) { main(()->{ Player player=Bukkit.getPlayer(id); if(player!=null && !builder) { lobby(player); Bukkit.getScheduler().runTaskLater(plugin,()->{ if(player.isOnline()) player.kickPlayer("PvP-Zuweisung nicht verfuegbar."); },40L); } }); throw ex; } main(()->{
            Player player=Bukkit.getPlayer(id); if(player==null) return;
            player.setGameMode(PvpAccess.joinMode(builder,found.isPresent()));
            PvpAccess.JoinDecision decision=PvpAccess.decide(found.isPresent(),builder);
            if(decision==PvpAccess.JoinDecision.BUILDER) return;
            if(decision==PvpAccess.JoinDecision.KICK) { lobby(player); Bukkit.getScheduler().runTaskLater(plugin,()->{ if(player.isOnline()) player.kickPlayer("Keine PvP-Zuweisung."); },40L); return; }
            PvpStore.Assignment a=found.get(); Session s=sessions.computeIfAbsent(a.matchId(),k->new Session(a)); players.put(id,s);
            if(Bukkit.getPlayer(a.a())!=null && Bukkit.getPlayer(a.b())!=null && players.get(a.a())==s && players.get(a.b())==s) begin(s);
        }); });
    }
    private void begin(Session s) {
        String id=s.assignment.matchId(); if(s.match!=null || s.finishing || !starting.add(id)) return;
        boolean alreadyRunning=s.claimed || s.assignment.status().equals("running");
        async(()->{
            boolean ok; try { ok=alreadyRunning || store.markStatus(id,"pending","running"); } catch(SQLException ex) { main(()->{ starting.remove(id); fail(s); }); throw ex; }
            main(()->{ starting.remove(id); if(s.finishing) return; if(!ok) { fail(s); return; } s.claimed=true;
                if(Bukkit.getPlayer(s.assignment.a())==null || Bukkit.getPlayer(s.assignment.b())==null) return;
                try { create(s); } catch(Exception ex) { plugin.getLogger().warning("PvP-Arena: "+ex.getMessage()); fail(s); }
            });
        });
    }
    private void create(Session s) {
        File[] files=arenas.listFiles((d,n)->n.endsWith(".yml")); if(files==null || files.length==0) throw new IllegalStateException("Keine Arena vorhanden");
        Collections.shuffle(Arrays.asList(files)); File selected=null; PvpLogic.Arena arena=null; List<String> blocks=null;
        for(File f:files) { try { YamlConfiguration candidate=YamlConfiguration.loadConfiguration(f); PvpLogic.Arena a=PvpLogic.Arena.read(candidate); List<String> b=candidate.getStringList("blocks");
            var min=a.region().min(); var max=a.region().max(); long size=((long)max.x()-min.x()+1)*((long)max.y()-min.y()+1)*((long)max.z()-min.z()+1);
            if(size==b.size() && size>0 && a.spawns().containsKey(1) && a.spawns().containsKey(2)) { selected=f;arena=a;blocks=b;break; }
        } catch(RuntimeException ignored) {} }
        if(selected==null) throw new IllegalStateException("Keine gueltige Arena vorhanden");
        YamlConfiguration kits=YamlConfiguration.loadConfiguration(kitsFile);
        if(!kits.isConfigurationSection("kits."+s.assignment.kitA()) || !kits.isConfigurationSection("kits."+s.assignment.kitB())) throw new IllegalStateException("Kit fehlt");
        String id=s.assignment.matchId().replaceAll("[^a-zA-Z0-9_-]", ""); if(id.length()<8) throw new IllegalStateException("Ungueltige Match-ID");
        String worldName="pvp_m_"+id.substring(0,8); if(Bukkit.getWorld(worldName)!=null || new File(Bukkit.getWorldContainer(),worldName).exists()) throw new IllegalStateException("Match-Welt existiert bereits: "+worldName);
        WorldCreator creator=new WorldCreator(worldName).generator(new ChunkGenerator() {}).generateStructures(false);
        s.world=Bukkit.createWorld(creator); if(s.world==null) throw new IllegalStateException("Welt konnte nicht erstellt werden"); s.world.setKeepSpawnInMemory(false);
        s.arena=arena; s.arenaName=selected.getName().substring(0,selected.getName().length()-4);
        var min=arena.region().min(); var max=arena.region().max(); int i=0;
        for(int x=min.x();x<=max.x();x++) for(int z=min.z();z<=max.z();z++) for(int yy=min.y();yy<=max.y();yy++) s.world.getBlockAt(x,yy,z).setBlockData(Bukkit.createBlockData(blocks.get(i++)),false);
        s.match=new PvpMatch(s.assignment.matchId(),s.assignment.a(),s.assignment.b(),s.assignment.kitA(),s.assignment.kitB(),new Random(),System.currentTimeMillis());
        round(s);
    }
    private void round(Session s) {
        if(s.finishing) return;
        Player a=Bukkit.getPlayer(s.assignment.a()), b=Bukkit.getPlayer(s.assignment.b());
        if(a==null || b==null) { if(s.match!=null) { s.match.disconnect(a==null?s.assignment.a():s.assignment.b(),System.currentTimeMillis()); finish(s); } return; }
        YamlConfiguration kits=YamlConfiguration.loadConfiguration(kitsFile); String key="kits."+s.match.currentKit();
        if(!kits.isConfigurationSection(key)) { fail(s); return; }
        prepare(a,s,1,kits,key); prepare(b,s,2,kits,key); s.match.startRound(System.currentTimeMillis()); s.active=true;
    }
    private void prepare(Player p,Session s,int number,YamlConfiguration kits,String key) {
        if(p.isDead()) p.spigot().respawn(); reset(p);
        p.getInventory().setStorageContents(items(kits.getList(key+".contents"),36)); p.getInventory().setArmorContents(items(kits.getList(key+".armor"),4));
        ItemStack off=kits.getItemStack(key+".offhand"); if(off!=null) p.getInventory().setItemInOffHand(off);
        p.setGameMode(GameMode.SURVIVAL); var spawn=s.arena.spawns().get(number); var min=s.arena.region().min();
        p.teleport(new Location(s.world,min.x()+spawn.x(),min.y()+spawn.y(),min.z()+spawn.z(),spawn.yaw(),spawn.pitch())); p.sendTitle("Runde "+s.match.roundNo(),"",10,40,10);
    }
    private static ItemStack[] items(List<?> list,int size) { ItemStack[] items=new ItemStack[size]; if(list!=null) for(int i=0;i<Math.min(size,list.size());i++) if(list.get(i) instanceof ItemStack item) items[i]=item; return items; }
    private void tick() {
        long now=System.currentTimeMillis();
        for(Session s:new ArrayList<>(sessions.values())) {
            if(s.finishing) continue;
            if(s.match==null) { if(now-s.waitingSince>=60_000) fail(s); continue; }
            if(s.active && s.match.tick(now)!=null) { message(s,"Unentschieden (5 min)"); afterRound(s); }
        }
    }
    @EventHandler public void death(PlayerDeathEvent e) {
        Session s=players.get(e.getEntity().getUniqueId()); if(s==null || !s.active || s.finishing) return;
        e.getDrops().clear(); e.setDroppedExp(0); e.setKeepInventory(false); e.getEntity().getInventory().clear();
        s.match.death(e.getEntity().getUniqueId(),System.currentTimeMillis()); afterRound(s);
        Bukkit.getScheduler().runTaskLater(plugin,()->{ if(e.getEntity().isOnline() && e.getEntity().isDead()) e.getEntity().spigot().respawn(); },1L);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        if(Bukkit.getOnlinePlayers().size() <= 1) idle.tick(System.currentTimeMillis(), false);
        Session s=players.remove(e.getPlayer().getUniqueId()); if(s==null || s.finishing) return;
        if(s.match!=null) { s.match.disconnect(e.getPlayer().getUniqueId(),System.currentTimeMillis()); finish(s); }
    }
    @EventHandler public void damage(EntityDamageEvent e) { if(e.getEntity() instanceof Player p) { Session s=players.get(p.getUniqueId()); if(s==null || !s.active || s.finishing) e.setCancelled(true); } }
    private void afterRound(Session s) { s.active=false; if(s.match.finished()) finish(s); else Bukkit.getScheduler().runTaskLater(plugin,()->round(s),60L); }
    private void finish(Session s) {
        if(s.finishing) return; s.finishing=true;s.active=false; long ended=System.currentTimeMillis();
        UUID winner=s.match.matchWinner(); message(s,winner==null?"Match unentschieden.":"Match gewonnen: "+Bukkit.getOfflinePlayer(winner).getName());
        async(()->{ store.saveMatch(s.match,s.arenaName,ended); store.markStatus(s.assignment.matchId(),"running","done"); });
        Bukkit.getScheduler().runTaskLater(plugin,()->{ sendPlayers(s); cleanup(s); },60L);
    }
    private void fail(Session s) {
        if(s.finishing) return; s.finishing=true;s.active=false;
        async(()->{ if(!store.markStatus(s.assignment.matchId(),"pending","failed")) store.markStatus(s.assignment.matchId(),"running","failed"); });
        message(s,"PvP-Match konnte nicht gestartet werden."); sendPlayers(s); cleanup(s);
    }
    private void sendPlayers(Session s) { for(UUID id:List.of(s.assignment.a(),s.assignment.b())) { Player p=Bukkit.getPlayer(id); if(p!=null) lobby(p); } }
    private void cleanup(Session s) {
        sessions.remove(s.assignment.matchId(),s); players.remove(s.assignment.a(),s); players.remove(s.assignment.b(),s);
        if(s.world!=null) { World w=s.world; for(Player p:w.getPlayers()) p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            if(Bukkit.unloadWorld(w,false)) { File folder=w.getWorldFolder(); Bukkit.getScheduler().runTaskAsynchronously(plugin,()->deleteWorld(folder.toPath())); }
            else plugin.getLogger().warning("PvP-Welt konnte nicht entladen werden: "+w.getName());
        }
    }
    private void deleteWorld(Path path) { try(var paths=Files.walk(path)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } catch(IOException e) { plugin.getLogger().warning("PvP-Welt konnte nicht geloescht werden: "+e.getMessage()); } }
    private void message(Session s,String text) { for(UUID id:List.of(s.assignment.a(),s.assignment.b())) { Player p=Bukkit.getPlayer(id); if(p!=null) p.sendMessage(text); } }
    private void lobby(Player p) { try { ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes); out.writeUTF("Connect");out.writeUTF("lobby");p.sendPluginMessage(plugin,"BungeeCord",bytes.toByteArray()); } catch(IOException e) { plugin.getLogger().warning("Lobby-Verbindung: "+e.getMessage()); } }
    private static void reset(Player p) { p.getInventory().clear();p.getInventory().setArmorContents(null);p.getInventory().setItemInOffHand(null); for(var effect:p.getActivePotionEffects()) p.removePotionEffect(effect.getType());p.setHealth(p.getMaxHealth());p.setFoodLevel(20);p.setSaturation(20);p.setFireTicks(0); }
    private void idleTick() {
        if(counting.compareAndSet(false,true)) async(()->{ try { pendingCheck=new PendingCheck(store.pendingCount()>0,System.nanoTime()); } catch(SQLException | RuntimeException e) { pendingCheck=new PendingCheck(null,0); throw e; } finally { counting.set(false); } });
        long now=System.currentTimeMillis(); boolean playersOnline=!Bukkit.getOnlinePlayers().isEmpty();
        PendingCheck check=pendingCheck;
        long age=check.pending()==null ? Long.MAX_VALUE : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-check.checkedAt());
        boolean draftsPending=editor.hasDrafts(), buildOccupied=!build.getPlayers().isEmpty();
        boolean elapsed=idle.tick(now,IdleStop.busy(playersOnline,null,Long.MAX_VALUE,draftsPending,buildOccupied));
        if((draftsPending || buildOccupied) && (lastEditorIdleLog==0 || now-lastEditorIdleLog>=60_000)) {
            plugin.getLogger().info("PvP-Autostopp ausgesetzt: Arena-Entwürfe oder Spieler in pvp_build vorhanden."); lastEditorIdleLog=now;
        }
        if(elapsed && !IdleStop.busy(playersOnline,check.pending(),age,draftsPending,buildOccupied)) { plugin.getLogger().info("PvP leer seit 120 s, stoppe Server"); Bukkit.shutdown(); }
    }
}
