package de.mcstack.craftcore.vip;

import java.util.List;
import java.util.Set;
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
    // VipStatusReconciler: a row the host runs nowhere is stopped on the second pass in a row, never on a guess
    @Test void planStopsAMissingWorldOnlyOnTheSecondPass() {
        var rows=List.of(new VipReconcile.Row("a","running","vipworld-1"),new VipReconcile.Row("b","running","vipworld-2"));
        var first=VipReconcile.plan(rows,List.of(),Set.of());
        assertTrue(first.updates().isEmpty(),"one miss may be a start that is just registering");
        assertEquals(Set.of("a","b"),first.missing());
        var second=VipReconcile.plan(rows,List.of(new VipReconcile.Slot("vipworld-1","sleeping",null)),first.missing());
        assertEquals(List.of(new VipReconcile.Update("a",VipReconcile.Kind.STOPPED,null),new VipReconcile.Update("b",VipReconcile.Kind.STOPPED,null)),second.updates());
    }
    @Test void planKeepsEverythingWhenTheHostCannotBeAsked() {
        var rows=List.of(new VipReconcile.Row("a","running","vipworld-1"));
        var p=VipReconcile.plan(rows,null,Set.of("a"));
        assertTrue(p.updates().isEmpty()); assertEquals(Set.of("a"),p.missing());
    }
    @Test void planFollowsTheHostSlotAndForgetsAWorldThatCameBack() {
        var rows=List.of(new VipReconcile.Row("a","running","vipworld-1"),new VipReconcile.Row("b","starting","vipworld-3"),new VipReconcile.Row("c","running","vipworld-4"));
        var slots=List.of(new VipReconcile.Slot("vipworld-2","online","a"),new VipReconcile.Slot("vipworld-3","online","b"),new VipReconcile.Slot("vipworld-4","online","c"));
        var p=VipReconcile.plan(rows,slots,Set.of("a","c"));
        assertEquals(List.of(new VipReconcile.Update("a",VipReconcile.Kind.RUNNING,"vipworld-2"),new VipReconcile.Update("b",VipReconcile.Kind.RUNNING,"vipworld-3")),p.updates());
        assertTrue(p.missing().isEmpty(),"c runs again: not stopped later");
    }
    @Test void reconcilerPassFixesStaleRowsAndLeavesLiveOnes() {
        var rows=new java.util.ArrayList<>(List.of(new VipReconcile.Row("dead-1","running","vipworld-1"),new VipReconcile.Row("dead-2","running","vipworld-2"),new VipReconcile.Row("live","running","vipworld-3")));
        var stopped=new java.util.ArrayList<String>();
        VipStatusReconciler.Store store=new VipStatusReconciler.Store() {
            public List<VipReconcile.Row> active() { return List.copyOf(rows); }
            public void stopped(String id) { stopped.add(id); rows.removeIf(r->r.id().equals(id)); }
            public void status(String id, String status, String slot) { fail("no slot change expected"); }
        };
        var r=new VipStatusReconciler(null,store,()->List.of(new VipReconcile.Slot("vipworld-3","online","live")));
        assertEquals(0,r.tick());
        assertEquals(2,r.tick());
        assertEquals(List.of("dead-1","dead-2"),stopped);
        assertEquals(0,r.tick(),"nothing left to fix");
        var down=new VipStatusReconciler(null,store,()->null);
        rows.add(new VipReconcile.Row("x","running","vipworld-9"));
        assertEquals(0,down.tick()); assertEquals(0,down.tick(),"host unreachable: never stopped");
    }
    @Test void stopTargetsOnlyTheSlotThatRunsThisWorld() {
        // Row of world A still says vipworld-1 after its idle stop; B now runs there (issue #152).
        var slots=List.of(new VipReconcile.Slot("vipworld-1","online","world-b"),
            new VipReconcile.Slot("vipworld-2","sleeping","world-a"));
        assertNull(VipReconcile.liveSlot("world-a",slots), "a stale row must never stop another owner's world");
        assertEquals("vipworld-1",VipReconcile.liveSlot("world-b",slots));
        assertEquals("vipworld-3",VipReconcile.liveSlot("world-c",List.of(new VipReconcile.Slot("vipworld-3","starting","world-c"))));
        assertNull(VipReconcile.liveSlot("world-a",null));
    }
}
