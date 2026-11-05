package de.mcstack.craftcore.vip;

import java.util.List;

/** Pure parts of the invite notice. */
public final class VipInvites {
    private VipInvites() {}

    public static String text(VipStore.Invite invite) {
        return invite.owner() + " hat dich in die VIP-Welt \"" + invite.world() + "\" eingeladen.";
    }

    /** UUID references cannot collide with owned or shared world names. */
    public static String joinCommand(String id) { return "/vw join #" + java.util.UUID.fromString(id); }

    /** The newest added_at seen, so each invite is told exactly once per server. */
    public static long nextMark(long since, List<VipStore.Invite> invites) {
        long mark = since;
        for (VipStore.Invite i : invites) mark = Math.max(mark, i.addedAt());
        return mark;
    }
}
