package de.mcstack.craftcore.pvp;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LobbyPvpTest {
    @Test void buildStartGuardRejectsConcurrentStartsAndAllowsRetry() {
        AtomicBoolean starting = new AtomicBoolean();
        assertTrue(LobbyPvp.tryStartBuild(starting));
        assertFalse(LobbyPvp.tryStartBuild(starting));
        starting.set(false);
        assertTrue(LobbyPvp.tryStartBuild(starting));
    }
    @Test void queueStatusShowsPositionOrJoinPrompt() {
        assertEquals(List.of("Platz 2 von 3"), LobbyPvp.queueStatus(2, 3));
        assertEquals(List.of("In der Queue: 0", "Wähle ein Kit oder klicke hier"), LobbyPvp.queueStatus(0, 0));
        assertEquals(List.of("In der Queue: 3", "Wähle ein Kit oder klicke hier"), LobbyPvp.queueStatus(0, 3));
    }
    @Test void queueReminderIncludesPosition() {
        assertEquals("Ein PvP-Match läuft gerade - du bist als Nächstes dran (Platz 3).", LobbyPvp.queueReminder(3));
    }
}
