package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class DailyRevenueTest {
  Connection setup(String name) throws Exception {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
    c.createStatement().execute("CREATE TABLE bank_revenue_daily(player VARCHAR(36),day_key VARCHAR(10),cents BIGINT NOT NULL,PRIMARY KEY(player,day_key))"); c.setAutoCommit(false);return c;
  }
  @Test void boundaryAndDaylightSaving() {
    ZoneId zone=ZoneId.of("Europe/Berlin");
    assertEquals("2026-10-27",DailyRevenue.dayKey(Instant.parse("2026-10-28T03:59:59Z"),zone,5));
    assertEquals("2026-10-28",DailyRevenue.dayKey(Instant.parse("2026-10-28T04:00:00Z"),zone,5));
    assertEquals("2026-03-29",DailyRevenue.dayKey(Instant.parse("2026-03-29T03:00:00Z"),zone,5));
  }
  @Test void combinesMaterialsAndRollsBack() throws Exception {
    try(Connection c=setup("revenue")) {
      DailyRevenue.lock(c,"p","day");assertTrue(DailyRevenue.reserve(c,"p","day",100000,150000));c.commit();
      DailyRevenue.lock(c,"p","day");assertFalse(DailyRevenue.reserve(c,"p","day",50001,150000));assertTrue(DailyRevenue.reserve(c,"p","day",50000,150000));c.rollback();
      assertEquals(50000,DailyRevenue.remaining(c,"p","day",150000));assertEquals(150000,DailyRevenue.remaining(c,"p","next",150000));assertEquals(150000,DailyRevenue.remaining(c,"other","day",150000));
    }
  }
  @Test void concurrentSalesCannotExceedLimit() throws Exception {
    try(Connection c=setup("race")) {
      DailyRevenue.lock(c,"p","day");c.commit();
      ExecutorService pool=Executors.newFixedThreadPool(2);
      try {
        Callable<Boolean> sale=()->{try(Connection x=DriverManager.getConnection("jdbc:h2:mem:race;MODE=MySQL;LOCK_TIMEOUT=5000")){x.setAutoCommit(false);DailyRevenue.lock(x,"p","day");boolean ok=DailyRevenue.reserve(x,"p","day",100000,150000);x.commit();return ok;}};
        Future<Boolean> a=pool.submit(sale),b=pool.submit(sale);assertNotEquals(a.get(),b.get());assertEquals(50000,DailyRevenue.remaining(c,"p","day",150000));
      } finally {pool.shutdownNow();}
    }
  }
}
