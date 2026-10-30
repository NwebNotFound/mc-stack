package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopBuyDispatchTest {
  static final String BUYER = "00000000-0000-0000-0000-000000000001";
  static final String LISTING = "00000000-0000-0000-0000-000000000004";

  static String[] args() { return new String[] {"buy", BUYER, LISTING, "1", "10", "key-1"}; }

  @Test void firstCallRepliesPendingAndStartsThePurchase() {
    for (String command : new String[] {"shop", "ah"}) {
      List<String> sent = new ArrayList<>();
      List<String> started = new ArrayList<>();
      String line = ShopBuyDispatch.complete(ShopBuyDispatch.Caller.CONSOLE, true, command, args(), request -> {
        assertEquals(command, request.kind());
        assertEquals("key-1", request.key());
        return null;
      }, request -> started.add(request.kind() + " " + request.key()), sent::add);
      assertEquals("PENDING key-1", line);
      assertEquals(List.of("PENDING key-1"), sent);
      assertEquals(List.of(command + " key-1"), started);
    }
  }

  @Test void retryReturnsTheStoredResultAndDoesNotStartAgain() {
    Map<String, String> store = new HashMap<>();
    List<String> started = new ArrayList<>();
    String[] args = args();
    assertEquals("PENDING key-1", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.RCON, true, "shop", args, request -> store.get(ShopBuyDispatch.cacheId(request)), request -> started.add(request.key()), line -> {}));
    store.put(ShopBuyDispatch.cacheId(RconPurchase.parse("shop", args)), "OK 1 10 9");
    List<String> sent = new ArrayList<>();
    assertEquals("OK 1 10 9", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.RCON, true, "shop", args, request -> store.get(ShopBuyDispatch.cacheId(request)), request -> started.add("again"), sent::add));
    store.put(ShopBuyDispatch.cacheId(RconPurchase.parse("shop", args)), "ERR NOT_FOUND");
    assertEquals("ERR NOT_FOUND", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.CONSOLE, true, "shop", args, request -> store.get(ShopBuyDispatch.cacheId(request)), request -> started.add("again"), sent::add));
    assertEquals(List.of("key-1"), started);
    assertEquals(List.of("OK 1 10 9", "ERR NOT_FOUND"), sent);
  }

  @Test void playerAndInvalidCallsDoNotStart() {
    int[] starts = {0};
    List<String> sent = new ArrayList<>();
    assertEquals("ERR INVALID", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.PLAYER, true, "shop", args(), request -> { starts[0]++; return "OK 1 10 9"; }, request -> starts[0]++, sent::add));
    assertEquals("ERR INVALID", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.OTHER, true, "shop", args(), request -> null, request -> starts[0]++, sent::add));
    assertEquals("ERR INVALID", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.CONSOLE, false, "shop", args(), request -> null, request -> starts[0]++, sent::add));
    String[] bad = args();
    bad[5] = "!";
    assertEquals("ERR INVALID", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.RCON, true, "ah", bad, request -> null, request -> starts[0]++, sent::add));
    assertEquals(0, starts[0]);
  }

  @Test void unfinishedOrPendingTextIsNotAStoredResult() {
    List<String> started = new ArrayList<>();
    String line = ShopBuyDispatch.complete(ShopBuyDispatch.Caller.RCON, true, "shop", args(), request -> "wird ausgeführt", request -> started.add(request.key()), message -> {});
    assertEquals("PENDING key-1", line);
    assertEquals(List.of("key-1"), started);
    assertFalse(line.toLowerCase().contains("wird ausgeführt"));
  }

  @Test void onePurchaseRunsUntilItIsStored() {
    RconPurchase.Request request = RconPurchase.parse("shop", args());
    String id = "flight-" + request.key();
    RconPurchase.Request flight = new RconPurchase.Request(request.kind(), request.buyer(), request.listing(), request.qty(), request.max(), id);
    assertTrue(ShopBuyDispatch.begin(flight));
    assertFalse(ShopBuyDispatch.begin(flight));
    ShopBuyDispatch.finish(flight, null);
    assertNull(ShopBuyDispatch.stored(flight));
    assertTrue(ShopBuyDispatch.begin(flight));
    ShopBuyDispatch.finish(flight, "OK 1 10 9");
    assertEquals("OK 1 10 9", ShopBuyDispatch.stored(flight));
    assertEquals("OK 1 10 9", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.CONSOLE, true, "shop", new String[] {"buy", BUYER, LISTING, "1", "10", id}, ShopBuyDispatch::stored, ignored -> { throw new AssertionError("stored result must not start again"); }, line -> {}));
  }
}
