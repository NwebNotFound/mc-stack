package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class GraveStoreTest {
  private Connection database(String name) throws Exception {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";DB_CLOSE_DELAY=-1");
    try(Statement s=c.createStatement()) {s.execute("CREATE TABLE graves(id BIGINT PRIMARY KEY,server VARCHAR(32),status VARCHAR(16), claiming_at BIGINT,delivered BOOLEAN,xp INT,claimed_by VARCHAR(36),xp_paid BOOLEAN DEFAULT TRUE)");s.execute("INSERT INTO graves(id,server,status,claiming_at,delivered,xp) VALUES(1,'survival','OPEN',NULL,FALSE,7)");
      s.execute("CREATE TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36),item_nbt CLOB,amount INT,source VARCHAR(32),status VARCHAR(16))");}
    return c;
  }
  @Test void doubleClickOnlyLocksOnce() throws Exception {
    try(Connection c=database("double")) {assertTrue(GraveStore.lock(c,1,1));assertFalse(GraveStore.lock(c,1,2));GraveStore.finish(c,1,"p",java.util.List.of(),0);try(ResultSet r=c.createStatement().executeQuery("SELECT status,delivered FROM graves")){assertTrue(r.next());assertEquals("CLAIMED",r.getString(1));assertTrue(r.getBoolean(2));}}
  }
  @Test void simultaneousPlayersOnlyOneWins() throws Exception {
    try(Connection setup=database("race")) {
      ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
      Callable<Boolean> attempt=()->{try(Connection c=DriverManager.getConnection("jdbc:h2:mem:race")){start.await();return GraveStore.lock(c,1,5);}};
      try {Future<Boolean> a=pool.submit(attempt),b=pool.submit(attempt);start.countDown();assertNotEquals(a.get(),b.get());}finally{pool.shutdownNow();}
    }
  }
  // Gatekeeper #170: items and XP debt commit together with the close-out; a later crash/disconnect loses nothing.
  @Test void closeOutPutsLootAndXpIntoDurableStateAtomically() throws Exception {
    try(Connection c=database("durable")) {
      assertTrue(GraveStore.lock(c,1,1));
      var ids=GraveStore.finish(c,1,"player-1",java.util.List.of(new GraveStore.Loot("AAA",3),new GraveStore.Loot("BBB",1)),7);
      assertEquals(2,ids.size());
      try(ResultSet r=c.createStatement().executeQuery("SELECT player_uuid,item_nbt,amount,source,status FROM pending_deliveries ORDER BY item_nbt")){
        assertTrue(r.next());assertEquals("player-1",r.getString(1));assertEquals("AAA",r.getString(2));assertEquals(3,r.getInt(3));assertEquals("grave",r.getString(4));assertEquals("offen",r.getString(5));
        assertTrue(r.next());assertEquals("BBB",r.getString(2));assertFalse(r.next());
      }
      // "crash" here: nothing was handed out, the XP is still owed to the claimer on this server
      var owed=GraveStore.unpaidXp(c,"survival","player-1");
      assertEquals(1,owed.size());assertEquals(1L,owed.get(0)[0]);assertEquals(7L,owed.get(0)[1]);
      assertTrue(GraveStore.unpaidXp(c,"lobby","player-1").isEmpty());assertTrue(GraveStore.unpaidXp(c,"survival","other").isEmpty());
      assertTrue(GraveStore.xpPaid(c,1));assertFalse(GraveStore.xpPaid(c,1),"paid only once");
      assertTrue(GraveStore.unpaidXp(c,"survival","player-1").isEmpty());
    }
  }
  @Test void failedCloseOutWritesNoDeliveries() throws Exception {
    try(Connection c=database("rollback")) {
      c.setAutoCommit(false);
      // not locked (still OPEN): close-out fails, and the caller's transaction rolls the inbox rows back
      assertThrows(SQLException.class,()->GraveStore.finish(c,1,"player-1",java.util.List.of(new GraveStore.Loot("AAA",1)),7));
      c.rollback();
      try(ResultSet r=c.createStatement().executeQuery("SELECT COUNT(*) FROM pending_deliveries")){r.next();assertEquals(0,r.getInt(1));}
    }
  }
  @Test void databaseFailureDoesNotGrantLock() throws Exception {
    try(Connection c=database("failure")){c.close();assertThrows(SQLException.class,()->GraveStore.lock(c,1,1));}
  }
}
