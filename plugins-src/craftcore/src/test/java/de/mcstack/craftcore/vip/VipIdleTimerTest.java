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
    @Test void stopsWhenInitialGraceExpiresWithoutJoin() {
        FakeClock clock=new FakeClock(); VipIdleTimer timer=new VipIdleTimer(clock,600_000,120_000);
        assertFalse(timer.tick(false)); clock.now=119_999; assertFalse(timer.tick(false));
        clock.now=120_000; assertTrue(timer.tick(false));
    }
    @Test void negativeGraceIsClampedToThirtySeconds() {
        assertMinimumGrace(-1_000);
    }
    @Test void tenSecondGraceIsClampedToThirtySeconds() {
        assertMinimumGrace(10_000);
    }
    private void assertMinimumGrace(long graceMillis) {
        FakeClock clock=new FakeClock(); VipIdleTimer timer=new VipIdleTimer(clock,600_000,graceMillis);
        clock.now=29_000; assertFalse(timer.tick(false));
        clock.now=30_000; assertTrue(timer.tick(false));
    }
    @Test void joinCancelsAndLastQuitRestartsInterval() {
        FakeClock clock=new FakeClock(); VipIdleTimer timer=new VipIdleTimer(clock,600_000,120_000);
        assertFalse(timer.tick(false)); clock.now=119_000; assertFalse(timer.tick(true));
        clock.now=1_000_000; assertFalse(timer.tick(true)); assertFalse(timer.tick(false));
        clock.now=1_599_999; assertFalse(timer.tick(false)); clock.now=1_600_000; assertTrue(timer.tick(false));
    }
}
