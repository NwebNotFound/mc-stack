package dev.nwebnotfound.link;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class LinkPlugin extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 2 || !(args[0].equals("add") || args[0].equals("remove"))) {
            sender.sendMessage("LINK_ERROR"); return true;
        }
        try {
            UUID uuid = UUID.fromString(args[1]);
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            player.setWhitelisted(args[0].equals("add"));
            if (!args[0].equals("add") && player.isOnline()) player.getPlayer().kickPlayer("Deine Discord-Spielerrolle wurde entfernt.");
            sender.sendMessage(player.isWhitelisted() == args[0].equals("add") ? "LINK_OK" : "LINK_ERROR");
        } catch (Exception ex) { sender.sendMessage("LINK_ERROR"); }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        try {
            Path path = Path.of("/link-pending", event.getUniqueId().toString().replace("-", ""));
            if (!Files.isRegularFile(path)) return;
            String[] values = Files.readString(path).trim().split("\\|");
            if (values.length == 2 && Long.parseLong(values[1]) > System.currentTimeMillis() / 1000) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "\u00a7a\u00a7lDiscord-Verkn\u00fcpfung\n\n\u00a77Dein Code:\n\u00a7e\u00a7l" + values[0].substring(0, 4) + " " + values[0].substring(4) + "\n\n\u00a7fSchick diesen Code Creepy auf Discord\n\u00a77(einfach in die DM an Creepy, danach bist du auf der Whitelist)");
            }
        } catch (Exception ex) { getLogger().warning("Verknüpfung konnte nicht gelesen werden: " + ex.getClass().getSimpleName()); }
    }
}
