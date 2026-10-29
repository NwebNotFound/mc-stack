package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class GraveStoreTest {
  private Connection database(String name) throws Exception {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";DB_CLOSE_DELAY=-1");
    try(Statement s=c.createStatement()) {s.execute("CREATE TABLE graves(id BIGINT PRIMARY KEY,status VARCHAR(16), claiming_at BIGINT,delivered BOOLEAN)");s.execute("INSERT INTO graves VALUES(1,'OPEN',NULL,FALSE)");}
    return c;
  }
  @Test void doubleClickOnlyLocksOnce() throws Exception {
    try(Connection c=database("double")) {assertTrue(GraveStore.lock(c,1,1));assertFalse(GraveStore.lock(c,1,2));GraveStore.finish(c,1);try(ResultSet r=c.createStatement().executeQuery("SELECT status,delivered FROM graves")){assertTrue(r.next());assertEquals("CLAIMED",r.getString(1));assertTrue(r.getBoolean(2));}}
  }
  @Test void simultaneousPlayersOnlyOneWins() throws Exception {
    try(Connection setup=database("race")) {
      ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
      Callable<Boolean> attempt=()->{try(Connection c=DriverManager.getConnection("jdbc:h2:mem:race")){start.await();return GraveStore.lock(c,1,5);}};
      try {Future<Boolean> a=pool.submit(attempt),b=pool.submit(attempt);start.countDown();assertNotEquals(a.get(),b.get());}finally{pool.shutdownNow();}
    }
  }
  @Test void databaseFailureDoesNotGrantLock() throws Exception {
    try(Connection c=database("failure")){c.close();assertThrows(SQLException.class,()->GraveStore.lock(c,1,1));}
  }
}
