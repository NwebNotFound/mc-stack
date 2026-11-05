package de.mcstack.craftcore.vip;

import de.mcstack.craftcore.ProxyChannel;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Lobby side of the VIP worlds: {@code /vw} and the clickable menus. */
public final class LobbyVip implements CommandExecutor {
    private final JavaPlugin plugin;
    private final VipStore store;
    private final VipModule module;
    private final VipGui gui;

    public LobbyVip(JavaPlugin plugin, VipStore.ConnectionSource source) {
        this.plugin = plugin;
        this.store = new VipStore(source);
        this.module = new VipModule(plugin, source, new Connector(), false, null);
        this.gui = module.gui();
    }

    public void start() {
        Objects.requireNonNull(plugin.getCommand("vw")).setExecutor(this);
        ProxyChannel.register(plugin);
        module.start();
    }

    public void shutdown() { module.shutdown(); }

    public VipGui gui() { return gui; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Nur fuer Spieler."); return true; }
        if (args.length == 0) { gui.openHub(player); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "menu", "gui" -> gui.openHub(player);
            case "list" -> list(player);
            case "create" -> {
                if (args.length < 2) { usage(player); return true; }
                gui.createWorld(player, args[1], args.length >= 3 ? args[2] : "normal");
            }
            case "rename" -> {
                if (args.length < 3) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.renameWorld(player, entry.id(), args[2]));
            }
            case "start" -> {
                if (args.length < 2) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.startWorld(player, entry.id()));
            }
            case "stop" -> {
                if (args.length < 2) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.stopWorld(player, entry.id()));
            }
            case "join" -> {
                if (args.length < 2) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.joinWorld(player, entry.id()));
            }
            case "invite" -> {
                if (args.length < 3) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.invite(player, entry.id(), args[2]));
            }
            case "kick" -> {
                if (args.length < 3) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.kick(player, entry.id(), args[2]));
            }
            case "members" -> {
                if (args.length < 2) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.openMembers(player, entry.id()));
            }
            case "addons" -> {
                if (args.length < 2) { usage(player); return true; }
                resolve(player, args[1], entry -> gui.openAddons(player, entry.id()));
            }
            default -> usage(player);
        }
        return true;
    }

    private void list(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String text;
            try {
                List<VipStore.World> worlds = store.list(player.getUniqueId());
                text = worlds.isEmpty() ? "Du hast noch keine VIP-Welten."
                    : "Deine VIP-Welten: " + String.join(", ", worlds.stream()
                        .map(w -> w.name() + " (" + w.status() + ")").toList());
            } catch (Exception e) {
                plugin.getLogger().warning("VIP-Liste: " + e.getClass().getSimpleName());
                text = "VIP-Welten nicht verfuegbar.";
            }
            String message = text;
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(message));
        });
    }

    /** Resolves a world name. The caller's own world wins; admins may reach any world via {@code owner:name}. */
    private void resolve(Player player, String name, Consumer<VipStore.Entry> action) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            VipStore.Entry entry = null;
            String problem = null;
            try {
                if (name.startsWith("#")) {
                    String id = UUID.fromString(name.substring(1)).toString();
                    entry = (VipGui.isAdmin(player) ? store.findById(id) : store.findJoinableById(player.getUniqueId(), id)).orElse(null);
                }
                for (VipStore.World world : name.startsWith("#") ? List.<VipStore.World>of() : store.list(player.getUniqueId())) {
                    if (!world.name().equalsIgnoreCase(name)) continue;
                    entry = store.findById(world.id()).orElse(null);
                    break;
                }
                if (entry == null) {
                    // A world the player was invited to: "/vw join <name>" from the invite notice.
                    for (VipStore.World world : store.listShared(player.getUniqueId())) {
                        if (!world.name().equalsIgnoreCase(name)) continue;
                        entry = store.findById(world.id()).orElse(null);
                        break;
                    }
                }
                if (entry == null && VipGui.isAdmin(player)) {
                    int colon = name.indexOf(':');
                    if (colon > 0) {
                        UUID owner = store.player(name.substring(0, colon)).orElse(null);
                        if (owner != null) entry = store.findByOwnerName(owner, name.substring(colon + 1)).orElse(null);
                        if (entry == null) problem = "Keine Welt " + name + ".";
                    } else {
                        List<UUID> owners = store.ownersOf(name);
                        if (owners.size() > 1) problem = "Mehrere Welten heissen " + name + ". Nutze <besitzer>:" + name + ".";
                        else if (owners.size() == 1) entry = store.findByOwnerName(owners.get(0), name).orElse(null);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("VIP-Welt suchen: " + e.getClass().getSimpleName());
            }
            VipStore.Entry found = entry;
            String hint = problem;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (found == null) { player.sendMessage(hint == null ? "Deine VIP-Welt wurde nicht gefunden." : hint); return; }
                action.accept(found);
            });
        });
    }

    private static void usage(Player player) {
        player.sendMessage("Benutzung: /vw [menu] | create <name> [normal|flat|void] | rename <welt> <name> | list | start|stop|join|members|addons <welt> | invite|kick <welt> <spieler>");
    }

    private final class Connector implements VipGui.Connector {
        public void connect(Player player, String slot) { send(player, slot); }
        public void lobby(Player player) { send(player, "lobby"); }
        private void send(Player player, String server) {
            ProxyChannel.sendOrTell(plugin, player, server);
        }
    }

}
