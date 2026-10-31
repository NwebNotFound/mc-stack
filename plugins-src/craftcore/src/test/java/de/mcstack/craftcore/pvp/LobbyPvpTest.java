package de.mcstack.craftcore.pvp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LobbyPvpTest {
    @Test void queueReminderIncludesPosition() {
        assertEquals("Ein PvP-Match laeuft gerade - du bist als Naechstes dran (Platz 3).", LobbyPvp.queueReminder(3));
    }
}
