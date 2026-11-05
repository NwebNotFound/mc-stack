package de.mcstack.status;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import static org.junit.jupiter.api.Assertions.*;
class StatusSwitchTest {
  @SuppressWarnings("unchecked") static <T> T fake(Class<T> type, BiFunction<String,Object[],Object> call) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(self,m,args)->{
      if(m.getName().equals("toString")) return type.getSimpleName();
      if(m.getName().equals("hashCode")) return System.identityHashCode(self);
      if(m.getName().equals("equals")) return self==args[0];
      return call.apply(m.getName(),args);
    });
  }
  @Test void realListenerDispatchesWhileStatusDatabaseIsStalledAndRejectsClientMessages() throws Exception {
    AtomicInteger connects=new AtomicInteger(); AtomicReference<ServerConnection> backend=new AtomicReference<>();
    RegisteredServer destination=fake(RegisteredServer.class,(m,a)->null);
    ConnectionRequestBuilder request=fake(ConnectionRequestBuilder.class,(m,a)->{if(m.equals("connect")){connects.incrementAndGet();return new CompletableFuture<>();}return null;});
    UUID id=UUID.randomUUID();
    Player player=fake(Player.class,(m,a)->switch(m){case "getUniqueId"->id;case "isActive"->true;case "getCurrentServer"->Optional.of(backend.get());case "createConnectionRequest"->request;default->null;});
    backend.set(fake(ServerConnection.class,(m,a)->m.equals("getPlayer")?player:null));
    ProxyServer proxy=fake(ProxyServer.class,(m,a)->m.equals("getServer")?(a[0].equals("missing")?Optional.empty():Optional.of(destination)):null);
    CountDownLatch blocked=new CountDownLatch(1), release=new CountDownLatch(1);
    StatusPlugin plugin=new StatusPlugin(proxy,NOPLogger.NOP_LOGGER,()->{
      blocked.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new SQLException("test outage");
    });
    ExecutorService executor=Executors.newSingleThreadExecutor();
    try {
      Future<?> refresh=executor.submit(plugin::refresh); assertTrue(blocked.await(2,TimeUnit.SECONDS));
      byte[] payload=HexFormat.of().parseHex("00000000000000070008737572766976616c");
      PluginMessageEvent message=new PluginMessageEvent(backend.get(),player,StatusPlugin.SWITCH_CHANNEL,payload);
      assertTimeoutPreemptively(Duration.ofMillis(500),()->plugin.switchMessage(message));
      assertEquals(1,connects.get()); assertFalse(message.getResult().isAllowed());
      PluginMessageEvent forged=new PluginMessageEvent(player,backend.get(),StatusPlugin.SWITCH_CHANNEL,payload);
      plugin.switchMessage(forged); assertEquals(1,connects.get()); assertFalse(forged.getResult().isAllowed());
      byte[] missing=HexFormat.of().parseHex("000000000000000800076d697373696e67");
      plugin.switchMessage(new PluginMessageEvent(backend.get(),player,StatusPlugin.SWITCH_CHANNEL,missing));
      plugin.switchMessage(new PluginMessageEvent(backend.get(),player,StatusPlugin.SWITCH_CHANNEL,payload));
      assertEquals(1,connects.get(),"a newer unavailable target still invalidates an older message");
      ServerConnection oldBackend=backend.get();
      backend.set(fake(ServerConnection.class,(m,a)->m.equals("getPlayer")?player:null));
      plugin.switchMessage(new PluginMessageEvent(oldBackend,player,StatusPlugin.SWITCH_CHANNEL,payload));
      assertEquals(1,connects.get(),"old backend messages cannot move the new session");
      assertTimeoutPreemptively(Duration.ofMillis(500),()->plugin.disconnect(new DisconnectEvent(player,DisconnectEvent.LoginStatus.values()[0])));
      release.countDown(); refresh.get(2,TimeUnit.SECONDS);
    } finally {release.countDown();executor.shutdownNow();}
  }
}
