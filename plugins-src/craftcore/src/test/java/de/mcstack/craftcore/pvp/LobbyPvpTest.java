package de.mcstack.craftcore.pvp;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LobbyPvpTest {
    @Test void queueStatusShowsPositionOrJoinPrompt() {
        assertEquals(List.of("Platz 2 von 3"), LobbyPvp.queueStatus(2, 3));
        assertEquals(List.of("In der Queue: 0", "Waehle ein Kit oder klicke hier"), LobbyPvp.queueStatus(0, 0));
        assertEquals(List.of("In der Queue: 3", "Waehle ein Kit oder klicke hier"), LobbyPvp.queueStatus(0, 3));
    }
    @Test void queueReminderIncludesPosition() {
        assertEquals("Ein PvP-Match laeuft gerade - du bist als Naechstes dran (Platz 3).", LobbyPvp.queueReminder(3));
    }
}
