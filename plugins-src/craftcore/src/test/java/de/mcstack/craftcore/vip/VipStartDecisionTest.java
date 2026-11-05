package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class VipStartDecisionTest {
  static VipReconcile.Slot slot(String s, String status, String world) { return new VipReconcile.Slot(s, status, world); }

  @Test void staleRunningRowStartsInsteadOfConnectingToADeadSlot() {
    var d = VipStartDecision.decide("running", "vipworld-2", "w1", List.of(slot("vipworld-2", "offline", null)));
    assertEquals(VipStartDecision.Step.START, d.step(), "Nils' bug 3: start used to connect to the dead slot");
    assertTrue(d.staleRow());
  }

  @Test void staleStartingRowStartsToo() {
    var d = VipStartDecision.decide("starting", "vipworld-1", "w1", List.of());
    assertEquals(VipStartDecision.Step.START, d.step());
    assertTrue(d.staleRow());
  }

  @Test void hostOnlineConnectsAndFixesTheRow() {
    var d = VipStartDecision.decide("stopped", null, "w1", List.of(slot("vipworld-1", "online", "w1")));
    assertEquals(VipStartDecision.Step.CONNECT, d.step());
    assertEquals("vipworld-1", d.slot());
    assertTrue(d.staleRow());
  }

  @Test void hostStartingWaits() {
    var d = VipStartDecision.decide("starting", "vipworld-1", "w1", List.of(slot("vipworld-1", "starting", "w1")));
    assertEquals(VipStartDecision.Step.WAIT, d.step());
    assertFalse(d.staleRow());
  }

  @Test void stoppedWorldStartsAndHostDownIsReported() {
    assertEquals(VipStartDecision.Step.START, VipStartDecision.decide("stopped", null, "w1", List.of()).step());
    assertEquals(VipStartDecision.Step.HOST_DOWN, VipStartDecision.decide("stopped", null, "w1", null).step());
  }

  @Test void deleteNeedsTheExactTypedNameAndAStoppedWorld() {
    assertEquals(VipRules.DeleteCheck.OK, VipRules.deleteCheck("Bauwelt", " Bauwelt ", "stopped"));
    assertEquals(VipRules.DeleteCheck.NAME_MISMATCH, VipRules.deleteCheck("Bauwelt", "bauwelt", "stopped"));
    assertEquals(VipRules.DeleteCheck.NAME_MISMATCH, VipRules.deleteCheck("Bauwelt", null, "stopped"));
    assertEquals(VipRules.DeleteCheck.RUNNING, VipRules.deleteCheck("Bauwelt", "Bauwelt", "running"));
    assertEquals(VipRules.DeleteCheck.RUNNING, VipRules.deleteCheck("Bauwelt", "Bauwelt", "starting"));
    assertEquals(VipRules.DeleteCheck.OK, VipRules.deleteCheck("Bauwelt", "Bauwelt", "error"));
  }

  @Test void softDeleteOnlyTouchesStoppedWorlds() {
    assertTrue(VipStore.MARK_DELETED.contains("status IN ('stopped','error')") && VipStore.MARK_DELETED.startsWith("UPDATE"));
  }
}
