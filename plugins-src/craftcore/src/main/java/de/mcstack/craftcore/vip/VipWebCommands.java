package de.mcstack.craftcore.vip;

import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * Applies one queue item. The rules live in {@link VipCommandRules}; this class loads the caller,
 * the rank and the world again at execution time and writes the result. Nothing here trusts a
 * rank, a UUID or a visibility that came in through the queue body.
 */
public final class VipWebCommands implements VipWebQueue.Applier {
    public interface Host {
        VipHostClient.Result start(String worldId);
        VipHostClient.Result stop(String slot);
    }
    public interface RankLookup { String rank(UUID player) throws Exception; }

    private final VipStore store;
    private final Host host;
    private final RankLookup ranks;
    private final VipStore.ConnectionSource links;
    private final int adminSlots;
    private final boolean trustedMayStart;

    public VipWebCommands(VipStore store, Host host, RankLookup ranks, VipStore.ConnectionSource links,
                          int adminSlots, boolean trustedMayStart) {
        this.store = Objects.requireNonNull(store);
        this.host = Objects.requireNonNull(host);
        this.ranks = Objects.requireNonNull(ranks);
        this.links = Objects.requireNonNull(links);
        this.adminSlots = adminSlots;
        this.trustedMayStart = trustedMayStart;
    }

    @Override
    public VipWebQueue.Outcome apply(VipCommand c) {
        try {
            Optional<UUID> linked = link(c.discordId());
            if (linked.isEmpty()) return VipWebQueue.Outcome.denied("not_linked");
            UUID actor = linked.get();
            String rank = String.valueOf(ranks.rank(actor));
            boolean admin = "admin".equalsIgnoreCase(rank);
            if ("create".equals(c.action())) return create(c, actor, rank, admin);
            return worldAction(c, actor, rank, admin);
        } catch (VipWorldSettings.InvalidSettingException e) {
            return VipWebQueue.Outcome.denied("invalid_setting");
        } catch (Exception e) {
            return VipWebQueue.Outcome.failed("host_unavailable");
        }
    }

    private VipWebQueue.Outcome create(VipCommand c, UUID actor, String rank, boolean admin) throws Exception {
        String type = c.type() == null || c.type().isBlank() ? "normal" : c.type();
        VipCommand withType = new VipCommand(c.requestId(), c.discordId(), c.createdAt(), c.action(), c.worldId(),
            c.name(), type, c.visibility(), c.role(), c.confirm(), c.settings(), c.hasSettings());
        int owned = store.count(actor);
        boolean taken = !store.ownersOf(c.name()).isEmpty();
        VipCommandRules.Context ctx = new VipCommandRules.Context(admin, false, false, trustedMayStart,
            rank, owned, VipCommandRules.slots(rank, adminSlots), 0, taken, false, false);
        String reason = VipCommandRules.decide(withType, ctx, null);
        if (!reason.isEmpty()) return VipWebQueue.Outcome.denied(reason);
        store.create(actor, c.name(), type);
        return VipWebQueue.Outcome.done();
    }

    private VipWebQueue.Outcome worldAction(VipCommand c, UUID actor, String rank, boolean admin) throws Exception {
        String id = c.worldId() == null ? "" : c.worldId();
        VipStore.Entry world = id.isBlank() ? null : store.findById(id).orElse(null);
        if (world == null) return VipWebQueue.Outcome.denied("world_not_found");
        boolean owner = world.owner().equals(actor);
        boolean scopedAdmin = admin && !owner;
        List<VipStore.Member> members = store.members(id);
        String target = c.name() == null ? "" : c.name().trim();
        boolean member = members.stream().anyMatch(m -> m.name() != null && m.name().equalsIgnoreCase(target));
        boolean trusted = members.stream().anyMatch(m ->
            m.name() != null && m.name().equalsIgnoreCase(target) && "trusted".equals(m.role()));
        boolean targetIsOwner = ownerName(id).map(n -> n.equalsIgnoreCase(target)).orElse(false);
        VipCommandRules.Context ctx = new VipCommandRules.Context(scopedAdmin, owner, trusted, trustedMayStart,
            rank, store.count(actor), VipCommandRules.slots(rank, adminSlots), members.size(),
            nameTakenByOther(c), targetIsOwner, member);
        String reason = VipCommandRules.decide(c, ctx, world);
        if (!reason.isEmpty()) return VipWebQueue.Outcome.denied(reason);

        switch (c.action()) {
            case "start" -> {
                if ("running".equals(world.status()) && world.slot() != null) return VipWebQueue.Outcome.done();
                VipHostClient.Result result = host.start(id);
                if (result == null) return VipWebQueue.Outcome.failed("host_unavailable");
                switch (result.outcome()) {
                    case ONLINE -> store.started(id, result.slot());
                    case STARTING -> store.status(id, "starting", result.slot());
                    case NO_FREE_SLOT -> { return VipWebQueue.Outcome.denied("no_free_slot"); }
                    case RAM_BUDGET -> { return VipWebQueue.Outcome.failed("ram_budget"); }
                    case WORLD_NOT_FOUND -> { return VipWebQueue.Outcome.denied("world_not_found"); }
                    default -> { return VipWebQueue.Outcome.failed("host_unavailable"); }
                }
            }
            case "stop" -> {
                if (!"running".equals(world.status()) && !"starting".equals(world.status())) return VipWebQueue.Outcome.done();
                if (world.slot() != null) {
                    VipHostClient.Result result = host.stop(world.slot());
                    if (result == null || result.outcome() == VipHostClient.Outcome.ERROR)
                        return VipWebQueue.Outcome.failed("host_unavailable");
                }
                store.stopped(id);
            }
            case "rename" -> store.rename(world.owner(), id, c.name().trim());
            case "settings" -> store.setSettings(id, VipCommandRules.settings(c, store.settings(id)));
            case "set_visibility" -> store.visibility(id, c.visibility());
            case "invite" -> {
                UUID targetId = store.player(target).orElse(null);
                if (targetId == null) return VipWebQueue.Outcome.denied("player_not_found");
                store.addMember(id, targetId, "member");
            }
            case "uninvite" -> {
                UUID targetId = store.player(target).orElse(null);
                if (targetId == null) return VipWebQueue.Outcome.denied("player_not_found");
                store.removeMember(id, targetId);
            }
            case "trust" -> {
                UUID targetId = store.player(target).orElse(null);
                if (targetId == null) return VipWebQueue.Outcome.denied("player_not_found");
                String role = c.role() == null ? "trusted" : c.role().toLowerCase(Locale.ROOT);
                store.setMemberRole(id, targetId, role);
            }
            case "delete" -> {
                store.delete(id);
                if (scopedAdmin) store.audit(id, "delete", actor, "", world.owner(), ownerName(id).orElse(""), "");
            }
            default -> { return VipWebQueue.Outcome.denied("unknown_action"); }
        }
        if (scopedAdmin && !"delete".equals(c.action())) {
            try {
                store.audit(id, c.action(), actor, "", world.owner(), ownerName(id).orElse(""),
                    c.action().equals("rename") ? c.name() : "");
            } catch (SQLException e) {
                return VipWebQueue.Outcome.failed("audit_failed");
            }
        }
        return VipWebQueue.Outcome.done();
    }

    private boolean nameTakenByOther(VipCommand c) throws SQLException {
        if (!"rename".equals(c.action()) || c.name() == null) return false;
        return store.ownersOf(c.name().trim()).size() > 0;
    }

    private Optional<String> ownerName(String worldId) throws SQLException {
        try (Connection conn = links.open();
             PreparedStatement s = conn.prepareStatement(
                 "SELECT p.name FROM vip_worlds w LEFT JOIN known_players p ON p.uuid=w.owner WHERE w.id=?")) {
            s.setString(1, worldId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.ofNullable(r.getString(1)) : Optional.empty();
            }
        }
    }

    private Optional<UUID> link(String discordId) throws SQLException {
        try (Connection c = links.open();
             PreparedStatement s = c.prepareStatement("SELECT uuid FROM discord_links WHERE discord_id=? LIMIT 1")) {
            s.setString(1, discordId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(UUID.fromString(r.getString(1))) : Optional.empty();
            }
        }
    }

    static Function<UUID, String> none() { return id -> ""; }
}
