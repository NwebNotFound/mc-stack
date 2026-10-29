package dev.mcstack.afk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

public final class AfkPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Long> activity = new HashMap<>();
    private final Map<UUID, Component> marked = new HashMap<>();
    private long markMillis, kickMillis;
    private boolean announce;
    private boolean saveOnEmpty, saveOnDisable;

    @Override public void onEnable() {
        saveDefaultConfig();
        markMillis = Math.max(0, getConfig().getLong("mark-seconds", 300)) * 1000;
        kickMillis = Math.max(0, getConfig().getLong("kick-seconds", 1800)) * 1000;
        announce = getConfig().getBoolean("announce", true);
        saveOnEmpty = getConfig().getBoolean("save-on-empty", false);
        saveOnDisable = getConfig().getBoolean("save-on-disable", false);
        long saveSeconds = Math.max(0, getConfig().getLong("save-interval-seconds", 0));
        if (saveSeconds > 0) {
            long savePeriod = Math.min(saveSeconds, Long.MAX_VALUE / 20) * 20;
            Bukkit.getScheduler().runTaskTimer(this, this::saveAll, savePeriod, savePeriod);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) activity.put(p.getUniqueId(), System.currentTimeMillis());
        long period = Math.max(1, getConfig().getLong("check-interval-seconds", 5)) * 20;
        Bukkit.getScheduler().runTaskTimer(this, this::check, period, period);
    }
    @Override public void onDisable() {
        if (saveOnDisable) saveAll();
        for (Player p : Bukkit.getOnlinePlayers()) clear(p, false);
    }
    private void saveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.saveData(); }
            catch (RuntimeException ex) { getLogger().log(java.util.logging.Level.SEVERE, "Spielerdaten konnten nicht gespeichert werden: " + p.getUniqueId(), ex); }
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            try { world.save(); }
            catch (RuntimeException ex) { getLogger().log(java.util.logging.Level.SEVERE, "Welt konnte nicht gespeichert werden: " + world.getName(), ex); }
        }
    }
    private void touch(Player p) {
        activity.put(p.getUniqueId(), System.currentTimeMillis());
        clear(p, true);
    }
    private void clear(Player p, boolean broadcast) {
        Component previous = marked.remove(p.getUniqueId());
        if (previous != null) {
            p.playerListName(previous);
            if (broadcast && announce) Bukkit.broadcast(Component.text(p.getName() + " ist wieder da.", NamedTextColor.YELLOW));
        }
    }
    private void check() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            long idle = now - activity.computeIfAbsent(p.getUniqueId(), x -> now);
            if (IdlePolicy.shouldKick(idle, kickMillis)) {
                clear(p, false);
                p.kick(Component.text("Wegen Inaktivitaet getrennt."));
            } else if (IdlePolicy.shouldMark(idle, markMillis) && !marked.containsKey(p.getUniqueId())) {
                Component previous = p.playerListName();
                if (previous == null) previous = Component.text(p.getName());
                marked.put(p.getUniqueId(), previous);
                p.playerListName(previous.append(Component.text(" [AFK]", NamedTextColor.GRAY)));
                if (announce) Bukkit.broadcast(Component.text(p.getName() + " ist nun AFK.", NamedTextColor.YELLOW));
            }
        }
    }
    @EventHandler public void join(PlayerJoinEvent e) { touch(e.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        clear(e.getPlayer(), false);
        activity.remove(e.getPlayer().getUniqueId());
        if (saveOnEmpty && Bukkit.getOnlinePlayers().size() == 1) {
            // Quit is fired before the player is removed; check again on the next tick.
            Bukkit.getScheduler().runTask(this, () -> {
                if (Bukkit.getOnlinePlayers().isEmpty()) saveAll();
            });
        }
    }
    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ() || e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch())) touch(e.getPlayer());
    }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEvent e) { touch(e.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void command(PlayerCommandPreprocessEvent e) { touch(e.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void swing(PlayerAnimationEvent e) { touch(e.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void click(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) touch(p); }
    @EventHandler(ignoreCancelled = true) public void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p) touch(p); }
    @EventHandler(ignoreCancelled = true) public void chat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> { Player p = Bukkit.getPlayer(id); if (p != null) touch(p); });
    }
}
