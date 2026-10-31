package de.mcstack.craftcore.pvp;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PvpDebug implements CommandExecutor {
    private final JavaPlugin plugin;
    private final PvpStore store;
    public PvpDebug(JavaPlugin plugin, PvpStore store) { this.plugin=plugin; this.store=store; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(sender instanceof Player) { sender.sendMessage("Nur Konsole"); return true; }
        if(args.length!=2 || !(args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("cancel"))) {
            sender.sendMessage("/pvpdebug join <uuid> | cancel <matchId>"); return true;
        }
        boolean join=args[0].equalsIgnoreCase("join");
        UUID player=null;
        if(join) { try { player=UUID.fromString(args[1]); } catch(IllegalArgumentException e) { sender.sendMessage("Ungueltige UUID"); return true; } }
        UUID id=player; String matchId=args[1];
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try {
                String result;
                if(join) {
                    var found=store.pendingFor(id);
                    result="PvP join: "+PvpAccess.decide(found.isPresent(),false)+" matchId="+found.map(PvpStore.Assignment::matchId).orElse("-");
                } else result=store.markStatus(matchId,"pending","failed") ? "PvP-Zuweisung abgebrochen: "+matchId : "Keine pending PvP-Zuweisung: "+matchId;
                Bukkit.getScheduler().runTask(plugin,()->{ plugin.getLogger().info("pvpdebug: "+result); sender.sendMessage(result); });
            } catch(Exception e) { Bukkit.getScheduler().runTask(plugin,()->{ plugin.getLogger().warning("pvpdebug: PvP-Datenbankfehler: "+e.getMessage()); sender.sendMessage("PvP-Datenbankfehler: "+e.getMessage()); }); }
        });
        return true;
    }
}
