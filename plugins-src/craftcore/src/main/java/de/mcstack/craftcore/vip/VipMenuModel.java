package de.mcstack.craftcore.vip;

import java.util.ArrayList;
import java.util.List;

/** Slot presentation independent of Bukkit and the database. */
public final class VipMenuModel {
    private VipMenuModel() {}
    public enum Kind { OCCUPIED, FREE, LOCKED }
    public record Slot(Kind kind, int worldIndex, String reason) {}

    public static List<Slot> slots(int allowed, int worlds) {
        int limit=Math.max(0, allowed);
        int count=Math.max(0, worlds);
        List<Slot> result=new ArrayList<>();
        if (limit==0) {
            result.add(new Slot(Kind.LOCKED, -1, "Kein VIP-Rang"));
        } else {
            for (int i=0; i<limit; i++) {
                result.add(i<count ? new Slot(Kind.OCCUPIED, i, "Slot belegt") : new Slot(Kind.FREE, -1, ""));
            }
            if (!VipRules.canCreate(count, limit)) result.add(new Slot(Kind.LOCKED, -1, "Limit erreicht"));
        }
        return List.copyOf(result);
    }
}
