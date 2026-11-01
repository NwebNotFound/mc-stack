package de.mcstack.craftcore;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresenceTest {
  @Test void recordUsesUtcAndBindsBoolean() throws Exception {
    List<Object> calls=new ArrayList<>();
    var statement=(java.sql.PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(proxy,method,args)-> {
      if(method.getName().equals("setBoolean")) calls.add(args[1]);
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
  }
  @Test void trackerResetsAndWritesJoinAndQuit() throws Exception {
    List<String> calls=new ArrayList<>(); UUID id=UUID.randomUUID();
    Presence.Tracker tracker=new Presence.Tracker(new Presence.Store() {
      public void reset() { calls.add("reset"); }
      public void write(UUID uuid,boolean online) { calls.add(uuid+":"+online); }
    });
    tracker.start(); tracker.join(id); tracker.quit(id); tracker.stop();
    assertEquals(List.of("reset",id+":true",id+":false","reset"),calls);
  }
}
