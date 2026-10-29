package de.mcstack.voicesculk;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.UUID;

public final class VoiceSculk extends JavaPlugin implements Listener, VoicechatPlugin {
    private Cooldown cooldown;
    private boolean whisperTriggers;
    private boolean groupTriggers;
    private double radiusSquared;
    // Only set on the server thread while dispatching our own game event.
    private Location voiceOrigin;
    private UUID voiceSpeaker;

    @Override public void onEnable() {
        saveDefaultConfig();
        cooldown = new Cooldown(getConfig().getLong("cooldown-ms", 1000));
        whisperTriggers = getConfig().getBoolean("whisper-triggers", false);
        groupTriggers = getConfig().getBoolean("group-triggers", false);
        double radius = getConfig().getDouble("radius", 8.0);
        if (!Double.isFinite(radius) || radius < 0) {
            getLogger().warning("Invalid radius; using 8 blocks.");
            radius = 8.0;
        }
        radiusSquared = radius * radius;
        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Voice chat API service unavailable; disabling addon.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        service.registerPlugin(this);
    }
    @Override public String getPluginId() { return "mcstack_voice_sculk"; }
    @Override public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }
    private void onMicrophone(MicrophonePacketEvent event) {
        var connection = event.getSenderConnection();
        var packet = event.getPacket();
        if (event.isCancelled() || connection == null || connection.getPlayer() == null || packet == null
                || packet.getOpusEncodedData() == null || packet.getOpusEncodedData().length == 0
                || (!whisperTriggers && packet.isWhispering()) || (!groupTriggers && connection.isInGroup())) return;
        UUID id = connection.getPlayer().getUuid();
        if (!cooldown.accept(id, System.nanoTime() / 1_000_000)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) return;
            voiceOrigin = player.getLocation();
            voiceSpeaker = id;
            try {
                player.getWorld().sendGameEvent(player, GameEvent.ENTITY_ACTION, voiceOrigin.toVector());
            } finally {
                voiceOrigin = null;
                voiceSpeaker = null;
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReceive(BlockReceiveGameEvent event) {
        if (voiceOrigin == null || event.getEvent() != GameEvent.ENTITY_ACTION || event.getEntity() == null
                || !event.getEntity().getUniqueId().equals(voiceSpeaker)) return;
        Material type = event.getBlock().getType();
        if (type != Material.SCULK_SENSOR && type != Material.CALIBRATED_SCULK_SENSOR) return;
        Location center = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (!center.getWorld().equals(voiceOrigin.getWorld()) || center.distanceSquared(voiceOrigin) > radiusSquared) {
            event.setCancelled(true);
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { cooldown.remove(event.getPlayer().getUniqueId()); }
}
