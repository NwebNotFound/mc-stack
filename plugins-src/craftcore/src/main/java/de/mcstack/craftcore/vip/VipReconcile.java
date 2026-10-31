package de.mcstack.craftcore.vip;

import java.util.List;

public final class VipReconcile {
    public record Slot(String slot, String status, String worldId) {}
    public enum Kind { UNCHANGED, STOPPED, RUNNING, STARTING, HOST_UNAVAILABLE }
    public record Action(Kind kind, String slot) {}
    public static Action decide(String dbStatus, String dbSlot, String worldId, List<Slot> hostSlots) {
        if (hostSlots == null) return new Action(Kind.HOST_UNAVAILABLE, null);
        for (Slot s : hostSlots) {
            if (worldId.equals(s.worldId()) && "online".equals(s.status()))
                return new Action(Kind.RUNNING, s.slot());
        }
        for (Slot s : hostSlots) {
            if (worldId.equals(s.worldId()) && "starting".equals(s.status()))
                return new Action(Kind.STARTING, s.slot());
        }
        if ("running".equals(dbStatus) || "starting".equals(dbStatus)) return new Action(Kind.STOPPED, null);
        return new Action(Kind.UNCHANGED, dbSlot);
    }
}
