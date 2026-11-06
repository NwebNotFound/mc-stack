package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Cross-server VIP invite notice (Nils, 05.11.2026: "can't invite players"). The invite is stored in
 * {@code vip_world_members}; every lobby/survival backend polls for rows newer than its last look and tells the invited
 * player, wherever they are, with a clickable join. No new table: {@code added_at} is the marker.
 */
public final class VipInviteNotices {
    static final long POLL_TICKS = 100;
    private final JavaPlugin plugin;
    private final VipStore store;
    private final boolean lobby;
    private final Map<UUID, Long> seenUntil = new ConcurrentHashMap<>();

    public VipInviteNotices(JavaPlugin plugin, VipStore.ConnectionSource source, boolean lobby) {
        this.plugin = plugin; this.store = new VipStore(source); this.lobby = lobby;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, POLL_TICKS, POLL_TICKS);
    }

    private void poll() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            // First sight of a player on this server: only invites from the last poll window count, no replay of old ones.
            long since = seenUntil.computeIfAbsent(id, k -> now - POLL_TICKS * 50);
            try {
                List<VipStore.Invite> invites = store.invitesSince(id, since);
                long until = VipInvites.nextMark(since, invites);
                seenUntil.put(id, until);
                for (VipStore.Invite invite : invites) Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) send(p, invite); });
            } catch (Exception e) { plugin.getLogger().warning("VIP-Einladungen: " + e.getClass().getSimpleName()); return; }
        }
        seenUntil.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void send(Player p, VipStore.Invite invite) {
        String command = lobby ? VipInvites.joinCommand(invite.id()) : "/lobby";
        p.sendMessage(Component.text("[VIP] " + VipInvites.text(invite), NamedTextColor.LIGHT_PURPLE)
            .append(Component.text(lobby ? " [Beitreten]" : " [Zur Lobby, dann /vw]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand(command))));
    }
}
