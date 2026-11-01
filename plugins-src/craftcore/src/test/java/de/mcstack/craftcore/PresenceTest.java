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
  @Test void joinAndQuitUpsertPerBackend() throws Exception {
    try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:presence_upsert_"+UUID.randomUUID()+";MODE=MariaDB")) {
      Presence.migrate(c);
      // H2 lacks MariaDB's UTC_TIMESTAMP; retain the real upsert and use its equivalent clock.
      var raw=c;
      var compatible=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)-> {
        if(method.getName().equals("prepareStatement")) args[0]=((String)args[0]).replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP(6)");
        try { return method.invoke(raw,args); } catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
      });
      UUID joined=UUID.randomUUID(), quitFirst=UUID.randomUUID();
      for(String server:List.of("lobby","survival")) {
        Presence.record(compatible,joined,server,true);
        Presence.record(compatible,quitFirst,server,false);
        try(var q=c.prepareStatement("UPDATE player_presence SET last_seen='2000-01-01 00:00:00' WHERE uuid=? AND server=?")) {
          q.setString(1,joined.toString()); q.setString(2,server); q.executeUpdate();
        }
        Presence.record(compatible,joined,server,false);
        Presence.record(compatible,joined,server,true);
      }
      try(var q=c.prepareStatement("SELECT uuid,server,online,last_seen FROM player_presence ORDER BY uuid,server"); var r=q.executeQuery()) {
        int count=0;
        while(r.next()) {
          assertEquals(r.getString(1).equals(joined.toString()),r.getBoolean(3));
          assertTrue(r.getTimestamp(4).after(java.sql.Timestamp.valueOf("2000-01-01 00:00:00"))); count++;
        }
        assertEquals(4,count);
      }
    }
  }
  @Test void backfillIsIdempotentAndPreservesExistingPresence() throws Exception {
    try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:presence_backfill_"+UUID.randomUUID()+";MODE=MariaDB")) {
      Presence.migrate(c);
      try(var s=c.createStatement()) {
        s.execute("CREATE TABLE player_playtime_world(uuid CHAR(36), server VARCHAR(16), updated_at TIMESTAMP)");
        s.execute("INSERT INTO player_playtime_world VALUES('a','lobby','2026-01-01 00:00:00'),('a','survival','2026-01-02 00:00:00'),('b','lobby','2026-01-03 00:00:00'),('b','pvp','2026-01-04 00:00:00')");
        s.execute("INSERT INTO player_presence VALUES('a','lobby','2025-01-01 00:00:00',TRUE)");
        String script=java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/backfill-player-presence.sql"));
        for(int i=0;i<2;i++) for(String statement:script.replaceAll("(?m)^--.*$","").split(";")) if(!statement.isBlank() && !statement.trim().startsWith("SET time_zone")) s.execute(statement);
        try(var r=s.executeQuery("SELECT uuid,server,last_seen,online FROM player_presence ORDER BY uuid,server")) {
          assertTrue(r.next()); assertEquals("a",r.getString(1)); assertEquals("lobby",r.getString(2)); assertEquals("2025-01-01 00:00:00.0",r.getTimestamp(3).toString()); assertTrue(r.getBoolean(4));
          assertTrue(r.next()); assertEquals("survival",r.getString(2)); assertFalse(r.getBoolean(4));
          assertTrue(r.next()); assertEquals("b",r.getString(1)); assertEquals("lobby",r.getString(2)); assertFalse(r.getBoolean(4));
          assertFalse(r.next());
        }
      }
    }
  }

}