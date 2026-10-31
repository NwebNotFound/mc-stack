package de.mcstack.craftcore.vip;

import java.io.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LobbyVip implements CommandExecutor {
    private final JavaPlugin plugin;
    private final VipStore store;
    private final VipHostClient host=new VipHostClient();
    private final Set<UUID> busy=ConcurrentHashMap.newKeySet();
    public LobbyVip(JavaPlugin plugin, VipStore.ConnectionSource source) { this.plugin=plugin; this.store=new VipStore(source); }
    public void start() { Objects.requireNonNull(plugin.getCommand("vw")).setExecutor(this); Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,"BungeeCord"); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Nur fuer Spieler."); return true; }
        UUID id=player.getUniqueId();
        if (args.length==0) { player.sendMessage("/vw create|rename|list|start|stop|join"); return true; }
        String action=args[0].toLowerCase(Locale.ROOT);
        if (!(action.equals("list") && args.length==1 || action.equals("create") && (args.length==2 || args.length==3) || action.equals("rename") && args.length==3 || Set.of("start","stop","join").contains(action) && args.length==2)) { player.sendMessage("Benutzung: /vw create <name> [normal|flat|void] | rename <welt> <name> | list | start|stop|join <welt>"); return true; }
        if ((action.equals("create") && !VipNames.validate(args[1])) || (action.equals("rename") && !VipNames.validate(args[2]))) { player.sendMessage("Der Name muss 3 bis 24 Zeichen lang sein und darf nur Buchstaben, Zahlen, _ und - enthalten."); return true; }
        String type=action.equals("create") && args.length==3 ? args[2].toLowerCase(Locale.ROOT) : "normal";
        if (action.equals("create") && !Set.of("normal","flat","void").contains(type)) { player.sendMessage("Welttyp: normal, flat oder void."); return true; }
        if (!busy.add(id)) { player.sendMessage("Eine VIP-Welt-Aktion laeuft bereits."); return true; }
        int slots=action.equals("create") ? VipPerks.slots(id) : 0;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try { execute(id,action,args,type,slots); }
            catch (SQLException e) { message(id,VipStore.duplicate(e) ? "Du hast bereits eine Welt mit diesem Namen." : "VIP-Welt-Datenbankfehler."); if (!VipStore.duplicate(e)) plugin.getLogger().warning("VIP-Welt-Datenbankfehler: "+e.getClass().getSimpleName()); }
            catch (Exception e) { plugin.getLogger().warning("VIP-Welt-Aktion fehlgeschlagen: "+e.getClass().getSimpleName()); message(id,"VIP-Welt-Aktion fehlgeschlagen."); }
            finally { busy.remove(id); }
        });
        return true;
    }
    private void execute(UUID owner, String action, String[] args, String type, int slots) throws SQLException {
        if (action.equals("create")) { if (!VipRules.canCreate(store.count(owner),slots)) { message(owner,"Du hast keinen freien VIP-Weltplatz."); return; } store.create(owner,args[1],type); message(owner,"VIP-Welt erstellt."); return; }
        if (action.equals("list")) { var worlds=store.list(owner); message(owner,worlds.isEmpty() ? "Du hast noch keine VIP-Welten." : "Deine VIP-Welten: "+String.join(", ",worlds.stream().map(w->w.name()+" ("+w.status()+")").toList())); return; }
        var found=store.find(owner,args[1]); if (found.isEmpty()) { message(owner,"Deine VIP-Welt wurde nicht gefunden."); return; }
        var world=found.get();
        if (Set.of("start","stop","join").contains(action)) {
            var decision=VipReconcile.decide(world.status(),world.slot(),world.id(),host.slots());
            switch (decision.kind()) {
                case HOST_UNAVAILABLE -> { message(owner,"VIP-Host nicht erreichbar, bitte spaeter erneut versuchen"); return; }
                case STOPPED -> { store.idleStopped(world.id()); world=new VipStore.World(world.id(),world.name(),"stopped",null); }
                case RUNNING -> { if (!"running".equals(world.status()) || !Objects.equals(world.slot(),decision.slot())) store.status(world.id(),"running",decision.slot()); world=new VipStore.World(world.id(),world.name(),"running",decision.slot()); }
                case STARTING -> { if (!"starting".equals(world.status()) || !Objects.equals(world.slot(),decision.slot())) store.status(world.id(),"starting",decision.slot()); world=new VipStore.World(world.id(),world.name(),"starting",decision.slot()); }
                case UNCHANGED -> { }
            }
        }
        switch(action) {
            case "rename" -> { store.rename(owner,world.id(),args[2]); message(owner,"VIP-Welt umbenannt."); }
            case "join" -> { if (VipRules.canJoin(world.status(),world.slot())) connect(owner,world.slot()); else if ("starting".equals(world.status()) && world.slot()!=null) waitForWorld(owner,world.id(),world.slot()); else message(owner,"Starte die VIP-Welt zuerst."); }
            case "stop" -> { if (!VipRules.canStop(world.status(),world.slot())) { message(owner,"Die VIP-Welt laeuft nicht."); return; } if (host.stop(world.slot()).outcome()!=VipHostClient.Outcome.STOPPED) { message(owner,"VIP-Welt konnte nicht gestoppt werden."); return; } store.stopped(world.id()); message(owner,"VIP-Welt gestoppt."); }
            case "start" -> startWorld(owner,world);
        }
    }
    private void startWorld(UUID owner, VipStore.World world) throws SQLException {
        if ("running".equals(world.status()) && world.slot()!=null) { connect(owner,world.slot()); return; }
        if ("starting".equals(world.status()) && world.slot()!=null) { waitForWorld(owner,world.id(),world.slot()); return; }
        if (!VipRules.canStart(world.status()) || world.slot()!=null) { message(owner,"Die VIP-Welt laeuft bereits oder startet gerade."); return; }
        var result=host.start(world.id());
        if (result.outcome()==VipHostClient.Outcome.NO_FREE_SLOT) { // TODO Warteschlange
            message(owner,"Alle Slots belegt"); return;
        }
        if (result.outcome()==VipHostClient.Outcome.RAM_BUDGET) { message(owner,"Gerade kein RAM frei"); return; }
        if (result.outcome()==VipHostClient.Outcome.WORLD_NOT_FOUND) { store.status(world.id(),"error",null); message(owner,"Weltordner fehlt auf dem Host"); return; }
        if (result.outcome()==VipHostClient.Outcome.ONLINE) { store.running(world.id(),result.slot()); connect(owner,result.slot()); return; }
        if (result.outcome()!=VipHostClient.Outcome.STARTING) { store.status(world.id(),"error",null); message(owner,"VIP-Welt konnte nicht gestartet werden."); return; }
        String slot=result.slot(); store.status(world.id(),"starting",slot); waitForWorld(owner,world.id(),slot);
    }
    private void waitForWorld(UUID owner, String worldId, String slot) throws SQLException {
        message(owner,"Deine Welt startet noch...");
        File config=new File(System.getenv().getOrDefault("VIP_CONFIG","config/vipworlds.yml"));
        int timeout=YamlConfiguration.loadConfiguration(config).getInt("start-timeout-seconds",180);
        try {
            if (VipReadiness.await(worldId,slot,timeout,host::slots,System::nanoTime,Thread::sleep)) {
                store.running(worldId,slot); connect(owner,slot); return;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        message(owner,"Start dauert zu lange, versuche /vw join gleich nochmal");
    }
    private void message(UUID id,String text) { Bukkit.getScheduler().runTask(plugin,()->{ Player p=Bukkit.getPlayer(id); if(p!=null) p.sendMessage(text); }); }
    private void connect(UUID id,String slot) { Bukkit.getScheduler().runTask(plugin,()->{ Player p=Bukkit.getPlayer(id); if(p==null) return; try { ByteArrayOutputStream bytes=new ByteArrayOutputStream(); try(DataOutputStream out=new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(slot); } p.sendPluginMessage(plugin,"BungeeCord",bytes.toByteArray()); } catch(IOException e) { p.sendMessage("Verbindung zur VIP-Welt fehlgeschlagen."); } }); }
}
