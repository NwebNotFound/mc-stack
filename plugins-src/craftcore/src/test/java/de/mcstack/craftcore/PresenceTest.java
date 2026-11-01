package de.mcstack.craftcore;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresenceTest {
  @Test void recordUsesUtcAndBindsBoolean() throws Exception {
    TimeZone original=TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
      List<Object> calls=new ArrayList<>();
      var statement=(java.sql.PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(proxy,method,args)-> {
        if(method.getName().equals("setBoolean")) calls.add(args[1]);
        if(method.getName().equals("setTimestamp") || method.getName().equals("setObject")) fail("Presence must use the database UTC clock");
        if(method.getName().equals("executeUpdate")) return 1;
        return null;
      });
      var connection=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)-> {
        if(method.getName().equals("prepareStatement")) { calls.add(args[0]); return statement; }
        return null;
      });
      Presence.record(connection,UUID.randomUUID(),"lobby",true);
      Presence.record(connection,UUID.randomUUID(),"lobby",false);
      assertTrue(((String)calls.get(0)).contains("UTC_TIMESTAMP(6)"));
      assertEquals(true,calls.get(1));
      assertEquals(false,calls.get(3));
    } finally { TimeZone.setDefault(original); }
  }
  @Test void trackerOrdersAndDrainsWithoutBlockingJoin() throws Exception {
    List<String> calls=Collections.synchronizedList(new ArrayList<>()); UUID id=UUID.randomUUID();
    var entered=new java.util.concurrent.CountDownLatch(1);
    var release=new java.util.concurrent.CountDownLatch(1);
    var errors=new ArrayList<Exception>();
    Presence.Tracker tracker=new Presence.Tracker(new Presence.Store() {
      public void reset() { calls.add("reset"); }
      public void write(UUID uuid,boolean online) throws Exception {
        if(online) { entered.countDown(); assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS)); }
        calls.add(uuid+":"+online);
      }
    },errors::add);
    tracker.start(); tracker.join(id);
    assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
    tracker.quit(id);
    assertFalse(tracker.stop(10,java.util.concurrent.TimeUnit.MILLISECONDS));
    tracker.join(UUID.randomUUID());
    release.countDown();
    assertTrue(tracker.stop(3,java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(List.of("reset",id+":true",id+":false","reset"),calls);
    assertTrue(errors.isEmpty());
  }
}
