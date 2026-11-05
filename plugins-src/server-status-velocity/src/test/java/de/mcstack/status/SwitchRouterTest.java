package de.mcstack.status;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SwitchRouterTest {
  @Test void newerReceiptWhileOldWorkIsPausedRejectsOldDestination() throws Exception {
    SwitchRouter router=new SwitchRouter(); UUID id=UUID.randomUUID(); Object session=new Object(), backend=new Object();
    List<String> sent=new ArrayList<>(); CountDownLatch paused=new CountDownLatch(1), resume=new CountDownLatch(1);
    ExecutorService executor=Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> old=executor.submit(() -> { paused.countDown(); assertTrue(resume.await(2,TimeUnit.SECONDS)); return router.dispatch(id,session,backend,1,()->true,()->sent.add("survival")); });
      assertTrue(paused.await(2,TimeUnit.SECONDS));
      assertTrue(router.dispatch(id,session,backend,2,()->true,()->sent.add("vipworld-1")));
      resume.countDown(); assertFalse(old.get(2,TimeUnit.SECONDS)); assertEquals(List.of("vipworld-1"),sent);
    } finally { resume.countDown(); executor.shutdownNow(); }
  }
  @Test void newerAcceptanceCannotInterleaveBetweenAcceptanceAndDispatch() throws Exception {
    SwitchRouter router=new SwitchRouter(); UUID id=UUID.randomUUID(); Object session=new Object(), backend=new Object();
    List<Integer> sent=new ArrayList<>(); CountDownLatch inside=new CountDownLatch(1), release=new CountDownLatch(1), started=new CountDownLatch(1);
    ExecutorService executor=Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> old=executor.submit(() -> router.dispatch(id,session,backend,1,()->true,()->{
        inside.countDown(); try { assertTrue(release.await(2,TimeUnit.SECONDS)); } catch(InterruptedException e){throw new RuntimeException(e);} sent.add(1);
      }));
      assertTrue(inside.await(2,TimeUnit.SECONDS));
      Future<Boolean> newer=executor.submit(()->{started.countDown();return router.dispatch(id,session,backend,2,()->true,()->sent.add(2));});
      assertTrue(started.await(2,TimeUnit.SECONDS)); assertThrows(TimeoutException.class,()->newer.get(100,TimeUnit.MILLISECONDS));
      release.countDown(); assertTrue(old.get(2,TimeUnit.SECONDS)); assertTrue(newer.get(2,TimeUnit.SECONDS)); assertEquals(List.of(1,2),sent);
    } finally { release.countDown(); executor.shutdownNow(); }
  }
  @Test void reconnectAndLateOldDisconnectAreSafe() {
    SwitchRouter router=new SwitchRouter(); UUID id=UUID.randomUUID(); Object old=new Object(), session=new Object(), backend=new Object();
    assertTrue(router.dispatch(id,session,backend,2,()->true,()->{})); router.forget(id,old);
    assertFalse(router.dispatch(id,session,backend,1,()->true,()->fail("old revision")));
    assertFalse(router.dispatch(id,old,new Object(),50,()->false,()->fail("old backend")));
    router.forget(id,session); assertTrue(router.dispatch(id,new Object(),new Object(),1,()->true,()->{}));
  }
}
