package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SellStackFlowTest {
  private final Queue<Runnable> worker=new ArrayDeque<>(), main=new ArrayDeque<>();
  private final SellStackFlow flow=new SellStackFlow(worker::add,main::add);
  private int transactions, taken, refunds, committed, unknown;
  private final List<String> pending=new ArrayList<>();
  private void start(String slot, SellTransaction.CommitStatus status, boolean throwsAfterCommit) {
    flow.start(slot,()->taken++,()->{
      transactions++;
      pending.add(slot);
      try {
        if (throwsAfterCommit) throw new IllegalStateException("commit acknowledgement lost");
        return status;
      } catch (IllegalStateException e) { return status; }
    },result->SellStackFlow.resolve(result,()->committed++,()->refunds++,()->unknown++));
  }
  private void drain() { while(!worker.isEmpty()) worker.remove().run(); while(!main.isEmpty()) main.remove().run(); }
  @Test void doubleClickStartsOneTransaction() {
    start("player:0",SellTransaction.CommitStatus.COMMITTED,false);
    start("player:0",SellTransaction.CommitStatus.COMMITTED,false);
    drain(); assertEquals(1,transactions); assertEquals(1,taken); assertEquals(1,committed); assertFalse(flow.busy("player:0"));
  }
  @Test void successCompletesWithoutRefund() {
    start("0",SellTransaction.CommitStatus.COMMITTED,false); drain();
    assertEquals(1,transactions); assertEquals(1,committed); assertEquals(0,refunds);
  }
  @Test void notCommittedRefunds() {
    start("0",SellTransaction.CommitStatus.NOT_COMMITTED,false); drain();
    assertEquals(1,refunds); assertEquals(0,committed);
  }
  @Test void exceptionAfterCommitDoesNotRefund() {
    start("0",SellTransaction.CommitStatus.COMMITTED,true); drain();
    assertEquals(1,committed); assertEquals(0,refunds);
  }
  @Test void unknownKeepsPendingAndDoesNotRefund() {
    start("0",SellTransaction.CommitStatus.UNKNOWN,true); drain();
    assertEquals(List.of("0"),pending); assertEquals(1,unknown); assertEquals(0,refunds);
  }
  @Test void sellAllRunsOneTransactionPerStack() {
    for(int i=0;i<7;i++) start("player:"+i,SellTransaction.CommitStatus.COMMITTED,false);
    drain(); assertEquals(7,transactions); assertEquals(7,taken); assertEquals(7,committed);
  }
}
