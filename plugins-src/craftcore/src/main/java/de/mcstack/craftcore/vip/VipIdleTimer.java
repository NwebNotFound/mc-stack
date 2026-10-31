package de.mcstack.craftcore.vip;

import java.time.Clock;
import java.util.Objects;

public final class VipIdleTimer {
    private final Clock clock;
    private final long delayMillis, graceMillis, startedAt;
    private Long idleSince;
    private boolean visited;
    public VipIdleTimer(Clock clock, long delayMillis, long graceMillis) {
        this.clock=Objects.requireNonNull(clock); this.delayMillis=delayMillis;
        this.graceMillis=Math.max(0,graceMillis); this.startedAt=clock.millis();
    }
    public boolean tick(boolean occupied) {
        if (occupied) { visited=true; idleSince=null; return false; }
        long now=clock.millis();
        if (!visited) return now-startedAt>=graceMillis;
        if (idleSince==null) idleSince=now;
        return now-idleSince>=delayMillis;
    }
}
