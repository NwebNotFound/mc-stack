package de.mcstack.craftcore.vip;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VipLogicTest {
    @Test void names() {
        assertTrue(VipNames.validate("Ab_9-")); assertTrue(VipNames.validate("a".repeat(24)));
        assertFalse(VipNames.validate(null)); assertFalse(VipNames.validate("ab")); assertFalse(VipNames.validate("a".repeat(25))); assertFalse(VipNames.validate("a b")); assertFalse(VipNames.validate("äbc"));
    }
    @Test void slots() {
        assertEquals(3,VipPerks.parseSlots("3")); assertEquals(0,VipPerks.parseSlots(null)); assertEquals(0,VipPerks.parseSlots("x")); assertEquals(0,VipPerks.parseSlots("-1")); assertEquals(0,VipPerks.parseSlots("999999999999999"));
    }
    @Test void rules() {
        assertTrue(VipRules.canCreate(2,3)); assertFalse(VipRules.canCreate(3,3)); assertFalse(VipRules.canCreate(0,0));
        assertTrue(VipRules.canStart("stopped")); assertFalse(VipRules.canStart("running"));
        assertTrue(VipRules.canJoin("running","vipworld-1")); assertFalse(VipRules.canJoin("starting","vipworld-1")); assertFalse(VipRules.canJoin("running",null));
        assertTrue(VipRules.canStop("starting","vipworld-1")); assertFalse(VipRules.canStop("stopped",null));
    }
    @Test void hostResponses() {
        assertEquals(new VipHostClient.Result(VipHostClient.Outcome.STARTING,"vipworld-1"),VipHostClient.parseStart(202,"{\"status\":\"starting\",\"slot\":\"vipworld-1\"}"));
        assertEquals(new VipHostClient.Result(VipHostClient.Outcome.ONLINE,"vipworld-1"),VipHostClient.parseStart(200,"{\"status\":\"running\",\"slot\":\"vipworld-1\"}"));
        assertEquals(VipHostClient.Outcome.WORLD_NOT_FOUND,VipHostClient.parseStart(404,"{\"status\":\"error\",\"code\":\"world_not_found\"}").outcome());
        assertEquals(VipHostClient.Outcome.ERROR,VipHostClient.parseStart(202,"{} ").outcome());
        assertEquals(VipHostClient.Outcome.NO_FREE_SLOT,VipHostClient.parseStart(409,"{\"error\":\"no_free_slot\"}").outcome());
        assertEquals(VipHostClient.Outcome.RAM_BUDGET,VipHostClient.parseStart(503,"{\"error\":{\"code\":\"ram_budget\"}}").outcome());
        assertEquals(VipHostClient.Outcome.ERROR,VipHostClient.parseStart(500,"oops").outcome());
        String status = "{\"slots\":[{\"slot\":\"vipworld-1\",\"status\":\"online\",\"worldId\":\"123e4567-e89b-12d3-a456-426614174000\"},{\"slot\":\"vipworld-2\",\"status\":\"starting\",\"worldId\":null},{\"slot\":\"vipworld-3\",\"status\":\"sleeping\",\"worldId\":null},{\"slot\":\"vipworld-4\",\"status\":\"offline\",\"worldId\":null}],\"maxRunning\":2,\"budgetOk\":true}";
        assertEquals(new VipHostClient.Result(VipHostClient.Outcome.ONLINE,"vipworld-1"),VipHostClient.parseStatus(200,status,"vipworld-1"));
        for (String slot : new String[]{"vipworld-2", "vipworld-3", "vipworld-4"}) assertEquals(new VipHostClient.Result(VipHostClient.Outcome.STARTING,slot),VipHostClient.parseStatus(200,status,slot));
        assertEquals(VipHostClient.Outcome.ERROR,VipHostClient.parseStatus(200,status,"vipworld-5").outcome());
        assertEquals(VipHostClient.Outcome.ONLINE,VipHostClient.parseStatus(200,"{\"slots\":{\"vipworld-1\":{\"status\":\"online\"}}}","vipworld-1").outcome());
        assertEquals(VipHostClient.Outcome.STARTING,VipHostClient.parseStatus(200,"{\"slot\":\"vipworld-1\",\"status\":\"starting\"}","vipworld-1").outcome());
        assertEquals(VipHostClient.Outcome.ERROR,VipHostClient.parseStatus(200,"{}","vipworld-1").outcome());
        assertEquals(VipHostClient.Outcome.STOPPED,VipHostClient.parseStop(200,"").outcome()); assertEquals(VipHostClient.Outcome.ERROR,VipHostClient.parseStop(500,"").outcome());
    }
}
