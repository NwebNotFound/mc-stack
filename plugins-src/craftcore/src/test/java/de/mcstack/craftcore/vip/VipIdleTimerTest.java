package de.mcstack.craftcore.vip;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VipIdleTimerTest {
    static final class FakeClock extends Clock {
        long now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }
    @Test void stopsAfterFullEmptyInterval() {
        FakeClock clock=new FakeClock(); VipIdleTimer timer=new VipIdleTimer(clock,600_000);
        assertFalse(timer.tick(false)); clock.now=599_999; assertFalse(timer.tick(false));
        clock.now=600_000; assertTrue(timer.tick(false));
    }
    @Test void joinCancelsAndLastQuitRestartsInterval() {
        FakeClock clock=new FakeClock(); VipIdleTimer timer=new VipIdleTimer(clock,600_000);
        assertFalse(timer.tick(false)); clock.now=599_000; assertFalse(timer.tick(true));
        clock.now=1_000_000; assertFalse(timer.tick(true)); assertFalse(timer.tick(false));
        clock.now=1_599_999; assertFalse(timer.tick(false)); clock.now=1_600_000; assertTrue(timer.tick(false));
    }
}
