package de.mcstack.craftcore.vip;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
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
    /** Highest of admin, mvp, vip; empty when the player has no VIP rank. Cached users only. */
    public static String rank(UUID id) {
        try {
            return resolveGroups(() -> groups(LuckPermsProvider.get().getUserManager().getUser(id)), () -> null);
        } catch (IllegalStateException | LinkageError e) { return ""; }
    }
    /**
     * Rank of a world owner who may be offline. {@code UserManager#getUser} only answers for
     * cached players, so the user is loaded when it is missing. That blocks on the LuckPerms
     * storage, so call it off the server thread (the join gate's executor does).
     */
    public static String rankLoaded(UUID id) {
        try {
            var manager = LuckPermsProvider.get().getUserManager();
            return resolveGroups(
                () -> groups(manager.getUser(id)),
                () -> groups(manager.loadUser(id).join()));
        } catch (IllegalStateException | LinkageError e) { return ""; }
        catch (RuntimeException e) { return ""; }
    }
    /**
     * Cached groups first, the loader only when the user is not cached. Pure so the
     * offline-owner path is testable without a LuckPerms instance.
     */
    public static String resolveGroups(Supplier<Collection<String>> cached, Supplier<Collection<String>> loader) {
        Collection<String> groups;
        try { groups = cached.get(); } catch (RuntimeException e) { groups = null; }
        if (groups == null) {
            try { groups = loader.get(); } catch (RuntimeException e) { return ""; }
        }
        return VipLimits.rank(groups);
    }
    private static Collection<String> groups(User user) {
        if (user == null) return null;
        List<String> names = user.getInheritedGroups(QueryOptions.nonContextual()).stream().map(Group::getName).toList();
        return names;
    }
    /** Slots from meta, with the admin floor. */
    public static int slots(UUID id, boolean admin, int adminSlots) {
        return VipLimits.slots(meta(id, "vipworlds.slots"), meta(id, "vipworld-slots"), admin, adminSlots);
    }
}
