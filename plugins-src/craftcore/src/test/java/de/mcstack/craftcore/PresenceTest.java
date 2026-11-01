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
  @Test void trackerOrdersAndDrainsBeforeSynchronousReset() throws Exception {
    List<String> calls=Collections.synchronizedList(new ArrayList<>()); UUID id=UUID.randomUUID();
    var errors=new ArrayList<Exception>();
    Presence.Tracker tracker=new Presence.Tracker(new Presence.Store() {
      public void reset() { calls.add("reset@"+Thread.currentThread().getName()); }
      public void write(UUID uuid,boolean online) { calls.add(uuid+":"+online); }
    },errors::add);
    tracker.start(); tracker.join(id); tracker.quit(id);
    assertTrue(tracker.stop(3,java.util.concurrent.TimeUnit.SECONDS));
    tracker.join(UUID.randomUUID());
    assertTrue(tracker.stop(3,java.util.concurrent.TimeUnit.SECONDS));
    String me=Thread.currentThread().getName();
    assertEquals(List.of("reset@presence-writer",id+":true",id+":false","reset@"+me),calls);
    assertTrue(errors.isEmpty());
  }
  @Test void shutdownResetRunsSynchronouslyEvenWhenWriterIsStuck() throws Exception {
    List<String> calls=Collections.synchronizedList(new ArrayList<>()); UUID id=UUID.randomUUID();
    var entered=new java.util.concurrent.CountDownLatch(1);
    var hang=new java.util.concurrent.CountDownLatch(1);
    Presence.Tracker tracker=new Presence.Tracker(new Presence.Store() {
      public void reset() { calls.add("reset@"+Thread.currentThread().getName()); }
      public void write(UUID uuid,boolean online) throws Exception {
        if(online) { entered.countDown(); hang.await(); }
        calls.add(uuid+":"+online);
      }
    },e -> {});
    tracker.join(id);
    assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
    tracker.quit(id);
    assertFalse(tracker.stop(50,java.util.concurrent.TimeUnit.MILLISECONDS));
    // reset already happened on this thread before stop() returned; queued quit was dropped
    assertEquals(List.of("reset@"+Thread.currentThread().getName()),calls);
    hang.countDown();
    Thread.sleep(100);
    assertEquals(List.of("reset@"+Thread.currentThread().getName()),calls);
  }
  @Test void startupResetOnlyClearsThisServer() throws Exception {
    try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:presence_"+UUID.randomUUID()+";MODE=MariaDB")) {
      Presence.migrate(c);
      try(var s=c.createStatement()) {
        s.execute("INSERT INTO player_presence VALUES ('a','lobby',CURRENT_TIMESTAMP,TRUE),('b','survival',CURRENT_TIMESTAMP,TRUE),('c','pvp',CURRENT_TIMESTAMP,TRUE),('d','vip',CURRENT_TIMESTAMP,TRUE)");
      }
      Presence.resetBackend(c,"pvp");
      Map<String,Boolean> online=new TreeMap<>();
      try(var s=c.createStatement(); var r=s.executeQuery("SELECT server,online FROM player_presence")) { while(r.next()) online.put(r.getString(1),r.getBoolean(2)); }
      assertEquals(Map.of("lobby",true,"survival",true,"pvp",false,"vip",true),online);
    }
  }
}