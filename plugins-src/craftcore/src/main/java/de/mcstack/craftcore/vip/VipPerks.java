package de.mcstack.craftcore.vip;

import java.util.UUID;
import net.luckperms.api.LuckPermsProvider;

public final class VipPerks {
    private VipPerks() {}
    public static int parseSlots(String value) {
        try { return Math.max(0, Integer.parseInt(value)); } catch (RuntimeException e) { return 0; }
    }
    public static int slots(UUID id) {
        try {
            var user = LuckPermsProvider.get().getUserManager().getUser(id);
            return user == null ? 0 : parseSlots(user.getCachedData().getMetaData().getMetaValue("vipworlds.slots"));
        } catch (IllegalStateException e) { return 0; }
    }
}
