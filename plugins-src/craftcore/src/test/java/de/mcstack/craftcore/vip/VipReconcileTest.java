package de.mcstack.craftcore.vip;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VipReconcileTest {
    @Test void idleStoppedWorldFreesSlot() {
        var slots=List.of(new VipReconcile.Slot("vipworld-1","sleeping","world"));
        assertEquals(new VipReconcile.Action(VipReconcile.Kind.STOPPED,null),VipReconcile.decide("running","vipworld-1","world",slots));
    }
    @Test void hostDownFailsClosed() {
        assertEquals(VipReconcile.Kind.HOST_UNAVAILABLE,VipReconcile.decide("running","vipworld-1","world",null).kind());
        assertNull(VipHostClient.parseSlots(503,"{}")); assertNull(VipHostClient.parseSlots(200,"{}"));
        assertNull(VipHostClient.parseSlots(200,"{\"slots\":[{}]}"));
    }
    @Test void onlineInAnotherSlotUpdatesDatabaseSlot() {
        var slots=VipHostClient.parseSlots(200,"{\"slots\":[{\"slot\":\"vipworld-2\",\"status\":\"online\",\"worldId\":\"world\"}]}");
        assertEquals(new VipReconcile.Action(VipReconcile.Kind.RUNNING,"vipworld-2"),VipReconcile.decide("running","vipworld-1","world",slots));
    }
    @Test void startingWorldRemainsStarting() {
        var slots=List.of(new VipReconcile.Slot("vipworld-2","starting","world"));
        assertEquals(new VipReconcile.Action(VipReconcile.Kind.STARTING,"vipworld-2"),VipReconcile.decide("running","vipworld-1","world",slots));
    }
}
