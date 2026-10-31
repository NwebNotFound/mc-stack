package de.mcstack.craftcore.vip;

import java.time.Clock;
import java.util.Objects;

public final class VipIdleTimer {
    private final Clock clock;
    private final long delayMillis;
    private Long idleSince;
    public VipIdleTimer(Clock clock, long delayMillis) { this.clock=Objects.requireNonNull(clock); this.delayMillis=delayMillis; }
    public boolean tick(boolean occupied) {
        if (occupied) { idleSince=null; return false; }
        long now=clock.millis();
        if (idleSince==null) idleSince=now;
        return now-idleSince>=delayMillis;
    }
}
