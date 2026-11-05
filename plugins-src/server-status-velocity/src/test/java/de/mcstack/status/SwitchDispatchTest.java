package de.mcstack.status;

import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SwitchDispatchTest {
  @Test void newerPublicationWhilePollerIsPausedPreventsOldDispatch() throws Exception {
    String url="jdbc:h2:mem:dispatch;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=500";
    try (Connection setup=DriverManager.getConnection(url); Statement s=setup.createStatement()) {
      s.execute("CREATE TABLE proxy_switch_requests(id BIGINT PRIMARY KEY, player VARCHAR(36))");
      s.execute("INSERT INTO proxy_switch_requests VALUES(1,'player')");
    }
    CountDownLatch paused=new CountDownLatch(1), resume=new CountDownLatch(1);
    AtomicBoolean sentOld=new AtomicBoolean();
    ExecutorService executor=Executors.newSingleThreadExecutor();
    try {
      Future<?> poll=executor.submit(() -> {
        try (Connection c=DriverManager.getConnection(url); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT id FROM proxy_switch_requests WHERE id=1")) {
          assertTrue(r.next()); long candidate=r.getLong(1);
          paused.countDown(); assertTrue(resume.await(3,TimeUnit.SECONDS));
          if (SwitchDispatch.latest(c,candidate,"player")) sentOld.set(true);
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      assertTrue(paused.await(3,TimeUnit.SECONDS));
      try (Connection c=DriverManager.getConnection(url); Statement s=c.createStatement()) {
        s.execute("INSERT INTO proxy_switch_requests VALUES(2,'player')");
        assertTrue(SwitchDispatch.latest(c,2,"player"));
      }
      resume.countDown(); poll.get(3,TimeUnit.SECONDS);
      assertFalse(sentOld.get(),"a candidate read before the new choice must not dispatch");
    } finally { resume.countDown(); executor.shutdownNow(); }
  }
}
