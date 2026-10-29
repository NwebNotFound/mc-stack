package de.mcstack.voicesculk;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class CooldownTest {
    @Test void rateLimitsEachPlayerAndAllowsAfterInterval() {
        Cooldown c = new Cooldown(1000); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(c.accept(a, 100)); assertFalse(c.accept(a, 1099));
        assertTrue(c.accept(b, 101)); assertTrue(c.accept(a, 1100));
        c.remove(a); assertTrue(c.accept(a, 1101));
    }
}
