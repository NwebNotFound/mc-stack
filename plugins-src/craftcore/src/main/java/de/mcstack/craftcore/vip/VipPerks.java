package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.UUID;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.query.QueryOptions;

public final class VipPerks {
    private VipPerks() {}
    public static int parseSlots(String value) {
        try { return Math.max(0, Integer.parseInt(value)); } catch (RuntimeException e) { return 0; }
    }
    public static int slots(UUID id) {
        try {
            var user = LuckPermsProvider.get().getUserManager().getUser(id);
            return user == null ? 0 : parseSlots(user.getCachedData().getMetaData().getMetaValue("vipworlds.slots"));
        } catch (IllegalStateException | LinkageError e) { return 0; }
    }
    /** LuckPerms meta value, null when LuckPerms or the key is missing. */
    public static String meta(UUID id, String key) {
        try {
            var user = LuckPermsProvider.get().getUserManager().getUser(id);
            return user == null ? null : user.getCachedData().getMetaData().getMetaValue(key);
        } catch (IllegalStateException | LinkageError e) { return null; }
    }
    /** Highest of admin, mvp, vip; empty when the player has no VIP rank. */
    public static String rank(UUID id) {
        try {
            var user = LuckPermsProvider.get().getUserManager().getUser(id);
            if (user == null) return "";
            List<String> groups = user.getInheritedGroups(QueryOptions.nonContextual()).stream().map(g -> g.getName()).toList();
            return VipLimits.rank(groups);
        } catch (IllegalStateException | LinkageError e) { return ""; }
    }
    /** Slots from meta, with the admin floor. */
    public static int slots(UUID id, boolean admin, int adminSlots) {
        return VipLimits.slots(meta(id, "vipworlds.slots"), meta(id, "vipworld-slots"), admin, adminSlots);
    }
}
