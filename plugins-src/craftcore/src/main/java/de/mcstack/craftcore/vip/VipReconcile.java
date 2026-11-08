package de.mcstack.craftcore.vip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    /** A database row that claims a world runs or starts. */
    public record Row(String id, String status, String slot) {}
    public record Update(String id, Kind kind, String slot) {}
    public record Plan(List<Update> updates, Set<String> missing) {}

    /**
     * One reconcile pass (VipStatusReconciler, every minute in the lobby). A row whose world the host runs nowhere is set
     * to stopped only when the previous pass already missed it too ({@code missingBefore}), so a start that is just being
     * registered on the host is never cut off. Host unreachable or its answer unusable ({@code hostSlots == null}):
     * nothing changes and the missing set is kept, a status is never guessed.
     */
    public static Plan plan(List<Row> rows, List<Slot> hostSlots, Set<String> missingBefore) {
        Set<String> before = missingBefore == null ? Set.of() : missingBefore;
        if (hostSlots == null) return new Plan(List.of(), Set.copyOf(before));
        List<Update> updates = new ArrayList<>();
        Set<String> missing = new HashSet<>();
        for (Row r : rows) {
            Action a = decide(r.status(), r.slot(), r.id(), hostSlots);
            switch (a.kind()) {
                case RUNNING -> { if (!"running".equals(r.status()) || !a.slot().equals(r.slot())) updates.add(new Update(r.id(), Kind.RUNNING, a.slot())); }
                case STARTING -> { if (!"starting".equals(r.status()) || !a.slot().equals(r.slot())) updates.add(new Update(r.id(), Kind.STARTING, a.slot())); }
                case STOPPED -> { if (before.contains(r.id())) updates.add(new Update(r.id(), Kind.STOPPED, null)); else missing.add(r.id()); }
                default -> { }
            }
        }
        return new Plan(List.copyOf(updates), Set.copyOf(missing));
    }

    public static String liveSlot(String worldId, List<Slot> hostSlots) {
        if (worldId == null || hostSlots == null) return null;
        for (Slot s : hostSlots) {
            if (worldId.equals(s.worldId()) && ("online".equals(s.status()) || "starting".equals(s.status()))) return s.slot();
        }
        return null;
    }
}
