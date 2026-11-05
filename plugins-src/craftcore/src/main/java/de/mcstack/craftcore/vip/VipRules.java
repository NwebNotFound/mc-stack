package de.mcstack.craftcore.vip;

public final class VipRules {
    private VipRules() {}
    public static boolean canCreate(int count, int slots) { return count >= 0 && count < slots; }
    public static boolean canStart(String status) { return "stopped".equals(status) || "error".equals(status); }
    public static boolean canJoin(String status, String slot) { return "running".equals(status) && slot != null && !slot.isBlank(); }
    public static boolean canStop(String status, String slot) { return ("running".equals(status) || "starting".equals(status) || "error".equals(status)) && slot != null && !slot.isBlank(); }

    public enum DeleteCheck { OK, NAME_MISMATCH, RUNNING }

    /** The typed name must match exactly (trimmed); only a stopped or failed world can be deleted. */
    public static DeleteCheck deleteCheck(String name, String typed, String status) {
        if (name == null || typed == null || !name.equals(typed.trim())) return DeleteCheck.NAME_MISMATCH;
        if (!"stopped".equals(status) && !"error".equals(status)) return DeleteCheck.RUNNING;
        return DeleteCheck.OK;
    }
}
