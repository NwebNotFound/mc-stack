package de.mcstack.craftcore;

import java.sql.SQLException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RconItemPreparationTest {
  @Test void encodingRunsOnMainExecutorBeforePurchaseCallback() throws Exception {
    try (ExecutorService main=Executors.newSingleThreadExecutor(r->new Thread(r,"test-server-main"))) {
      int[] calls={0};
      var remaining=RconItemPreparation.prepare(new RconItemPreparation.Snapshot("original",10),4,
        (item,amount)->main.submit(()->{
          assertEquals("test-server-main",Thread.currentThread().getName());
          calls[0]++; return item+":"+amount;
        }).get());
      assertEquals(1,calls[0],"encoding completes before purchase locks are acquired");
      assertEquals("original:6",remaining.encode("original",6));
      assertEquals(1,calls[0],"transaction callback only returns prepared bytes");
      assertThrows(SQLException.class,()->remaining.encode("edited",6));
      assertThrows(SQLException.class,()->remaining.encode("original",5));
    }
  }

  @Test void missingOrFullPurchaseNeverCallsBukkit() throws Exception {
    RconPurchase.Remaining forbidden=(item,amount)->{fail("no partial stack to encode");return null;};
    for (var snapshot:new RconItemPreparation.Snapshot[]{null,new RconItemPreparation.Snapshot("item",4)}) {
      var remaining=RconItemPreparation.prepare(snapshot,4,forbidden);
      assertThrows(SQLException.class,()->remaining.encode("item",1));
    }
  }

  @Test void schedulingFailurePropagatesBeforePurchase() {
    assertThrows(TimeoutException.class,()->RconItemPreparation.prepare(
      new RconItemPreparation.Snapshot("item",10),4,(item,amount)->{throw new TimeoutException();}));
  }
}
