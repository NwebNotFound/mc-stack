package de.mcstack.craftcore.vip;

import java.util.Locale;

/** Who may invite, kick, start, and manage a world. Pure rules with the API reason strings. */
public final class VipMemberRules {
    public enum Role { MEMBER, TRUSTED }

    public record Verdict(boolean allowed, String reason) {
        public static Verdict ok() { return new Verdict(true, ""); }
        public static Verdict no(String reason) { return new Verdict(false, reason); }
    }

    public static final int MAX_MEMBERS = 50;

    private VipMemberRules() {}

    public static Verdict invite(boolean owner, boolean admin, boolean alreadyMember, int members) {
        if (!owner && !admin) return Verdict.no("not_owner");
        if (alreadyMember) return Verdict.no("already_member");
        if (members >= MAX_MEMBERS) return Verdict.no("member_limit");
        return Verdict.ok();
    }

    public static Verdict kick(boolean owner, boolean admin, boolean targetIsOwner, boolean targetIsSelf) {
        if (!owner && !admin) return Verdict.no("not_owner");
        if (targetIsOwner) return Verdict.no("is_owner");
        if (targetIsSelf) return Verdict.no("is_owner");
        return Verdict.ok();
    }

    public static Verdict mayStart(boolean owner, boolean admin, boolean trusted, boolean trustedMayStart) {
        if (owner || admin) return Verdict.ok();
        if (trusted && trustedMayStart) return Verdict.ok();
        return Verdict.no(trusted ? "not_trusted" : "not_owner");
    }

    public static Verdict mayManage(boolean owner, boolean admin) {
        return owner || admin ? Verdict.ok() : Verdict.no("not_owner");
    }

    public static Role role(String value) {
        return "trusted".equalsIgnoreCase(value == null ? "" : value.trim()) ? Role.TRUSTED : Role.MEMBER;
    }

    public static String roleName(Role role) { return role == Role.TRUSTED ? "trusted" : "member"; }

    /** Trims and drops section-sign formatting codes from a typed player name. */
    public static String cleanName(String name) {
        if (name == null) return "";
        String stripped = name.replaceAll("\u00a7.", "").replaceAll("\\p{Cf}", "");
        return stripped.trim();
    }

    public static boolean validName(String name) {
        return VipNames.validate(cleanName(name));
    }

    public static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
