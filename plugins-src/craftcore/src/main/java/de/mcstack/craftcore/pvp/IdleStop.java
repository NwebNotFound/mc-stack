package de.mcstack.craftcore.pvp;

public final class IdleStop {
    public static final long DEFAULT_GRACE_MS = 120_000;
    private final long graceMs;
    private Long idleSince;

    public IdleStop(long graceMs) { this.graceMs = graceMs; }

    public boolean tick(long nowMs, boolean busy) {
        if (busy) {
            idleSince = null;
            return false;
        }
        if (idleSince == null) idleSince = nowMs;
        return nowMs - idleSince >= graceMs;
    }
}
