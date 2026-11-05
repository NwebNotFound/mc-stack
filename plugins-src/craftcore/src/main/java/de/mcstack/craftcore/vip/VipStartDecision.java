package de.mcstack.craftcore.vip;

import java.util.List;

/**
 * What the "Welt starten" button does. It trusts the host, not the database row: a row left on
 * {@code running} or {@code starting} by a crashed or idle-stopped slot used to make the button connect to a dead slot
 * (nothing happened) until the player pressed "Beitreten" once, which reconciled the row (Nils' report, 05.11.2026).
 */
public final class VipStartDecision {
    public enum Step { CONNECT, WAIT, START, HOST_DOWN }
    public record Decision(Step step, String slot, boolean staleRow) {}

    private VipStartDecision() {}

    public static Decision decide(String dbStatus, String dbSlot, String worldId, List<VipReconcile.Slot> hostSlots) {
        VipReconcile.Action a = VipReconcile.decide(dbStatus, dbSlot, worldId, hostSlots);
        return switch (a.kind()) {
            case RUNNING -> new Decision(Step.CONNECT, a.slot(), !"running".equals(dbStatus) || !a.slot().equals(dbSlot));
            case STARTING -> new Decision(Step.WAIT, a.slot(), !a.slot().equals(dbSlot));
            case HOST_UNAVAILABLE -> new Decision(Step.HOST_DOWN, null, false);
            case STOPPED -> new Decision(Step.START, null, true);
            case UNCHANGED -> new Decision(Step.START, null, false);
        };
    }
}
