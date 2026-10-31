package de.mcstack.craftcore.vip;

import java.io.File;
import java.time.Clock;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class VipIdleStop implements Listener {
    private final JavaPlugin plugin;
    private final VipIdleTimer timer;
    public VipIdleStop(JavaPlugin plugin) {
        this.plugin=plugin;
        long minutes=10;
        long grace=120;
        File config=new File(System.getenv().getOrDefault("VIP_CONFIG","config/vipworlds.yml"));
        try { YamlConfiguration yaml=new YamlConfiguration(); yaml.load(config); minutes=positive(yaml.getString("idle-minutes"),minutes); grace=minimumGrace(yaml.getString("join-grace-seconds"),grace); }
        catch (Exception ignored) { }
        minutes=positive(System.getenv("VIP_IDLE_MINUTES"),minutes);
        timer=new VipIdleTimer(Clock.systemUTC(),minutes*60_000L,grace*1_000L);
    }
    private static long positive(String value,long fallback) {
        try { long n=Long.parseLong(value); return n>0 && n<=Long.MAX_VALUE/60_000 ? n : fallback; }
        catch (RuntimeException ex) { return fallback; }
    }
    private static long minimumGrace(String value,long fallback) {
        try { long n=Long.parseLong(value); return n<=Long.MAX_VALUE/1_000 ? Math.max(30,n) : fallback; }
        catch (RuntimeException ex) { return fallback; }
    }
    public void start() {
        Bukkit.getPluginManager().registerEvents(this,plugin);
        timer.tick(!Bukkit.getOnlinePlayers().isEmpty());
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            if (timer.tick(!Bukkit.getOnlinePlayers().isEmpty())) { plugin.getLogger().info("VIP-Welt ist leer, Server wird gestoppt."); Bukkit.shutdown(); }
        },20L,20L);
    }
    @EventHandler public void join(PlayerJoinEvent event) { timer.tick(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { if (Bukkit.getOnlinePlayers().size()<=1) timer.tick(false); }
}
