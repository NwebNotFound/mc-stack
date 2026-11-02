package de.mcstack.craftcore.vip;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * In-world menu on a VIP slot: {@code /vw} or the nether star opens the manage screen
 * of the world this server belongs to. Invite and kick work from inside the world.
 */
public final class VipWorldMenu implements CommandExecutor, Listener {
    private final JavaPlugin plugin;
    private final VipModule module;
    private final VipGui gui;
    private final String worldId;

    public VipWorldMenu(JavaPlugin plugin, VipStore.ConnectionSource source) {
        this.plugin = plugin;
        this.worldId = System.getenv("CRAFTCORE_VIP_WORLD_ID");
        this.module = new VipModule(plugin, source, new Connector(), true, worldId);
        this.gui = module.gui();
    }

    public void start() {
        Objects.requireNonNull(plugin.getCommand("vw")).setExecutor(this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new VipStarGuard(), plugin);
        module.start();
        if (worldId == null || worldId.isBlank()) {
            plugin.getLogger().warning("CRAFTCORE_VIP_WORLD_ID fehlt; das In-World-Menue ist nicht verfuegbar.");
        }
    }

    public void shutdown() { module.shutdown(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Nur fuer Spieler."); return true; }
        if (worldId == null || worldId.isBlank()) { player.sendMessage("Diesem Server ist keine VIP-Welt zugeordnet."); return true; }
        if (args.length == 0) { gui.openWorld(player, worldId); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "menu", "gui", "world" -> gui.openWorld(player, worldId);
            case "members" -> gui.openMembers(player, worldId);
            case "addons" -> gui.openAddons(player, worldId);
            case "invite" -> {
                if (args.length < 2) { usage(player); return true; }
                gui.invite(player, worldId, args[1]);
            }
            case "kick" -> {
                if (args.length < 2) { usage(player); return true; }
                gui.kick(player, worldId, args[1]);
            }
            case "start" -> gui.startWorld(player, worldId);
            case "stop" -> gui.stopWorld(player, worldId);
            case "join" -> gui.joinWorld(player, worldId);
            default -> usage(player);
        }
        return true;
    }

    private static void usage(Player player) {
        player.sendMessage("Benutzung: /vw [menu|members|addons|start|stop] | invite <spieler> | kick <spieler>");
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        if (worldId == null || worldId.isBlank()) return;
        VipStarGuard.give(event.getPlayer(), VipStarGuard.KEY);
    }

    @EventHandler
    public void use(PlayerInteractEvent event) {
        if (worldId == null || worldId.isBlank()) return;
        if (event.getHand() != EquipmentSlot.HAND || !VipStarGuard.tagged(event.getItem())) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        gui.openWorld(event.getPlayer(), worldId);
    }

    private final class Connector implements VipGui.Connector {
        public void connect(Player player, String slot) { send(player, slot); }
        public void lobby(Player player) { send(player, "lobby"); }
        private void send(Player player, String server) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(server); }
                player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            } catch (IOException e) {
                player.sendMessage("Verbindung zu " + server + " fehlgeschlagen.");
            }
        }
    }
}
