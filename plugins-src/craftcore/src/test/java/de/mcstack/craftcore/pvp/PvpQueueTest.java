package de.mcstack.craftcore.pvp;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvpQueueTest {
    @Test void fifoRejoinAndLeave() {
        PvpQueue queue = new PvpQueue();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertEquals(0, queue.size());
        queue.join(a, "old");
        assertEquals(1, queue.size());
        queue.join(b, "b");
        queue.join(c, "c");
        queue.join(a, "new");
        assertEquals(3, queue.size());
        assertEquals(new PvpQueue.Pair(a, "new", b, "b"), queue.poll().orElseThrow());
        assertEquals(1, queue.size());
        assertFalse(queue.contains(a));
        assertTrue(queue.contains(c));
        assertTrue(queue.leave(c));
        assertFalse(queue.leave(c));
        assertEquals(0, queue.size());
    }

    @Test void pollNeedsTwo() {
        PvpQueue queue = new PvpQueue();
        assertTrue(queue.poll().isEmpty());
        UUID a = UUID.randomUUID();
        queue.join(a, "kit");
        assertTrue(queue.poll().isEmpty());
        assertTrue(queue.contains(a));
    }
}
