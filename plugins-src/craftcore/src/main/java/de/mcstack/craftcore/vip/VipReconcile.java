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

    /**
     * The slot the host really runs (or starts) this world on, or null when it runs it nowhere. A stop must only ever
     * target this slot: the database slot can be stale after an idle stop and may meanwhile host another owner's world.
     */
    public static String liveSlot(String worldId, List<Slot> hostSlots) {
        if (worldId == null || hostSlots == null) return null;
        for (Slot s : hostSlots) {
            if (worldId.equals(s.worldId()) && ("online".equals(s.status()) || "starting".equals(s.status()))) return s.slot();
        }
        return null;
    }
}
