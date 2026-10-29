package dev.mcstack.afk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdlePolicyTest {
    @Test void thresholdsAndDisabled() {
        assertFalse(IdlePolicy.shouldMark(299999, 300000));
        assertTrue(IdlePolicy.shouldMark(300000, 300000));
        assertFalse(IdlePolicy.shouldKick(299999, 300000));
        assertTrue(IdlePolicy.shouldKick(300000, 300000));
        assertFalse(IdlePolicy.shouldKick(1799999, 1800000));
        assertTrue(IdlePolicy.shouldKick(1800000, 1800000));
        assertFalse(IdlePolicy.shouldKick(Long.MAX_VALUE, 0));
        assertFalse(IdlePolicy.shouldMark(Long.MAX_VALUE, 0));
    }
}
