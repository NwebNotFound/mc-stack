package de.mcstack.craftcore.vip;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static de.mcstack.craftcore.vip.VipMenuModel.Kind.*;

class VipMenuModelTest {
    @Test void noRankIsLocked() {
        var slots=VipMenuModel.slots(0,0);
        assertEquals(1,slots.size());
        assertEquals(LOCKED,slots.get(0).kind());
        assertEquals("Kein VIP-Rang",slots.get(0).reason());
    }
    @Test void emptySingleSlotIsFree() {
        assertEquals(FREE,VipMenuModel.slots(1,0).get(0).kind());
    }
    @Test void fullSingleSlotHasNoFreeSlot() {
        var slots=VipMenuModel.slots(1,1);
        assertEquals(OCCUPIED,slots.get(0).kind());
        assertTrue(slots.stream().noneMatch(s->s.kind()==FREE));
        assertEquals("Limit erreicht",slots.get(1).reason());
    }
    @Test void threeSlotsWithOneWorld() {
        var slots=VipMenuModel.slots(3,1);
        assertEquals(3,slots.size());
        assertEquals(OCCUPIED,slots.get(0).kind());
        assertEquals(FREE,slots.get(1).kind());
        assertEquals(FREE,slots.get(2).kind());
    }
    @Test void excessWorldsStayVisible() {
        var slots=VipMenuModel.slots(1,3);
        assertEquals(4,slots.size());
        for (int i=0;i<3;i++) { assertEquals(OCCUPIED,slots.get(i).kind()); assertEquals(i,slots.get(i).worldIndex()); }
        assertEquals("Limit erreicht",slots.get(3).reason());
    }
    @Test void rankLostKeepsOwnedWorlds() {
        var slots=VipMenuModel.slots(0,2);
        assertEquals(3,slots.size());
        assertEquals(OCCUPIED,slots.get(0).kind());
        assertEquals(OCCUPIED,slots.get(1).kind());
        assertEquals("Kein VIP-Rang",slots.get(2).reason());
    }
}
