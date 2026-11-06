package de.mcstack.craftcore.vip;

import java.util.Locale;
import java.util.Set;

/**
 * Who may run which queue action, and the checks that do not need a server. Every method returns
 * the contract's reason string, or an empty string when the action may run. Keeping this pure is
 * what lets the denied cases be tested for every action without a database.
 */
public final class VipCommandRules {
    public static final int MAX_MEMBERS = 12;
    public static final String OK = "";

    /** What the checks need to know about the caller and the target world. */
    public record Context(boolean admin, boolean owner, boolean trusted, boolean trustedMayStart,
                          String actorRank, String ownerRank, int ownedWorlds, int slots, int members,
                          boolean nameTaken, boolean targetIsOwner, boolean alreadyMember) {
    }

    private VipCommandRules() {}

    /** World slots per rank; admins never drop below the configured admin floor. */
    public static int slots(String rank, int adminSlots) {
        String r = rank == null ? "" : rank.toLowerCase(Locale.ROOT);
        return switch (r) {
            case "admin" -> Math.max(1, Math.min(VipLimits.MAX_SLOTS, Math.max(3, adminSlots)));
            case "mvp" -> 3;
            case "vip" -> 1;
            default -> 0;
        };
    }

    public static boolean hasRank(String rank) {
        return slots(rank, 3) > 0;
    }

    /**
     * The decision for one command. {@code world} is the stored world, or null for {@code create}
     * and for ids that no longer exist.
     */
    public static String decide(VipCommand c, Context ctx, VipStore.Entry world) {
        if (c == null) return "invalid_request";
        if (!c.known()) return "unknown_action";
        switch (c.action()) {
            case "create" -> {
                if (!ctx.admin() && !hasRank(ctx.actorRank())) return "no_rank";
                if (!VipNames.validate(c.name())) return "invalid_name";
                if (c.type() != null && !VipCommand.TYPES.contains(c.type())) return "invalid_type";
                if (ctx.nameTaken()) return "name_taken";
                if (ctx.ownedWorlds() >= ctx.slots()) return "no_free_slot";
                return OK;
            }
            default -> { /* world actions below */ }
        }
        if (world == null) return "world_not_found";
        return switch (c.action()) {
            case "start" -> {
                if (ctx.admin()) yield OK;
                // Vertrag: nur Owner oder ein vertrauenswuerdiges Mitglied mit Flag darf starten.
                if (!ctx.owner() && !ctx.trusted()) yield "not_trusted";
                if (!ctx.owner() && !ctx.trustedMayStart()) yield "not_trusted";
                // Der Start haengt am Rang des Owners, nicht am Rang des Startenden.
                if (!hasRank(ctx.ownerRank())) yield "no_rank";
                yield OK;
            }
            case "stop", "rename", "settings", "set_visibility", "invite", "uninvite", "trust", "delete" -> {
                if (!ctx.owner() && !ctx.admin()) yield "not_owner";
                String reason = OK;
                switch (c.action()) {
                    case "rename" -> {
                        if (!VipNames.validate(c.name())) reason = "invalid_name";
                        else if (ctx.nameTaken()) reason = "name_taken";
                    }
                    case "set_visibility" -> {
                        if (!VipCommand.VISIBILITIES.contains(c.visibility())) reason = "invalid_visibility";
                    }
                    case "invite" -> {
                        if (ctx.alreadyMember()) reason = "already_member";
                        else if (ctx.members() >= MAX_MEMBERS) reason = "member_limit";
                        else if (!VipMemberRules.validName(c.name())) reason = "invalid_player";
                    }
                    case "uninvite" -> {
                        if (ctx.targetIsOwner()) reason = "is_owner";
                        else if (!ctx.alreadyMember()) reason = "member_not_found";
                        else if (!VipMemberRules.validName(c.name())) reason = "invalid_player";
                    }
                    case "trust" -> {
                        if (c.role() != null && !VipCommand.ROLES.contains(c.role().toLowerCase(Locale.ROOT))) reason = "invalid_role";
                        else if (!ctx.alreadyMember()) reason = "member_not_found";
                    }
                    case "delete" -> {
                        String typed = c.confirm() == null ? "" : c.confirm().trim();
                        if (typed.isEmpty() || !typed.equals(world.name())) reason = "invalid_name";
                    }
                    default -> { /* settings: Werte pruefen sich beim Merge */ }
                }
                yield reason;
            }
            default -> "unknown_action";
        };
    }

    /** Settings from the queue are browser input: unknown keys or bad values are denied. */
    public static VipWorldSettings settings(VipCommand c, VipWorldSettings stored) throws VipWorldSettings.InvalidSettingException {
        VipWorldSettings base = stored == null ? VipWorldSettings.DEFAULTS : stored;
        if (!c.hasSettings() || c.settings() == null) return base;
        return base.merge(c.settings());
    }

    public static Set<String> roles() { return VipCommand.ROLES; }
}
