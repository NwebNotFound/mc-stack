package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VipStartProgressTest {
    @Test void startingFractionGrowsAndStaysBelowReady() {
        assertEquals(0.05f, VipStartProgress.starting("vipworld-1", 0, 180_000).fraction(), 0.001f);
        assertEquals(0.5f, VipStartProgress.starting("vipworld-1", 90_000, 180_000).fraction(), 0.001f);
        assertEquals(0.95f, VipStartProgress.starting("vipworld-1", 900_000, 180_000).fraction(), 0.001f);
        assertEquals(VipStartProgress.Phase.STARTING, VipStartProgress.starting("vipworld-1", 1, 0).phase());
    }

    @Test void titleCarriesTheSlotAndPercent() {
        var update = VipStartProgress.starting("vipworld-2", 90_000, 180_000);
        assertTrue(update.title().contains("50%"));
        assertTrue(update.message().contains("vipworld-2"));
        assertEquals("vipworld-2", update.slot());
    }

    @Test void readyIsFullAndConnects() {
        var update = VipStartProgress.ready("vipworld-3");
        assertEquals(VipStartProgress.Phase.READY, update.phase());
        assertEquals(1f, update.fraction());
        assertTrue(update.message().contains("vipworld-3"));
    }

    @Test void everyFailureReasonHasGermanText() {
        for (String reason : new String[]{VipStartCoordinator.TIMEOUT, VipStartCoordinator.NO_FREE_SLOT,
                VipStartCoordinator.RAM_BUDGET, VipStartCoordinator.WORLD_NOT_FOUND,
                VipStartCoordinator.HOST_UNAVAILABLE, VipStartCoordinator.CANCELLED, "unknown"}) {
            var update = VipStartProgress.failed(reason);
            assertEquals(VipStartProgress.Phase.FAILED, update.phase());
            assertEquals(0f, update.fraction());
            assertFalse(update.message().isBlank(), reason);
        }
        assertTrue(VipStartProgress.reasonText(VipStartCoordinator.TIMEOUT).toLowerCase().contains("lange"));
        assertTrue(VipStartProgress.reasonText(VipStartCoordinator.NO_FREE_SLOT).toLowerCase().contains("slots"));
    }
}
