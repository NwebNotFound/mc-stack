package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopBuyDispatchTest {
  static final String BUYER = "00000000-0000-0000-0000-000000000001";
  static final String LISTING = "00000000-0000-0000-0000-000000000004";

  static String[] args() { return new String[] {"buy", BUYER, LISTING, "1", "10", "key-1"}; }

  @Test void consoleAndRconReceiveThePurchaseLineBeforeTheCallReturns() {
    for (String command : new String[] {"shop", "ah"}) {
      for (ShopBuyDispatch.Caller caller : List.of(ShopBuyDispatch.Caller.CONSOLE, ShopBuyDispatch.Caller.RCON)) {
        List<String> sent = new ArrayList<>();
        boolean[] sentDuringCall = {false};
        String line = ShopBuyDispatch.complete(caller, true, command, args(), request -> {
          assertEquals(command, request.kind());
          assertEquals(BUYER, request.buyer());
          assertEquals(1, request.qty());
          return "OK 1 10 9";
        }, message -> { sent.add(message); sentDuringCall[0] = true; });
        assertTrue(sentDuringCall[0]);
        assertEquals("OK 1 10 9", line);
        assertEquals(List.of("OK 1 10 9"), sent);
        assertFalse(line.toLowerCase().contains("wird ausgeführt"));
      }
    }
  }

  @Test void playerReceivesTheRefusalOnTheCallerWithoutBuying() {
    int[] buys = {0};
    List<String> sent = new ArrayList<>();
    String line = ShopBuyDispatch.complete(ShopBuyDispatch.Caller.PLAYER, true, "shop", args(), request -> { buys[0]++; return "OK 1 10 9"; }, sent::add);
    assertEquals("ERR INVALID", line);
    assertEquals(List.of("ERR INVALID"), sent);
    assertEquals(0, buys[0]);
  }

  @Test void otherSendersLobbyAndBadArgsDoNotBuy() {
    int[] buys = {0};
    List<String> sent = new ArrayList<>();
    assertEquals("ERR INVALID", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.OTHER, true, "shop", args(), request -> { buys[0]++; return "OK 1 10 9"; }, sent::add));
    assertEquals("ERR INVALID", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.CONSOLE, false, "shop", args(), request -> { buys[0]++; return "OK 1 10 9"; }, sent::add));
    String[] bad = args();
    bad[5] = "!";
    assertEquals("ERR INVALID", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.RCON, true, "ah", bad, request -> { buys[0]++; return "OK 1 10 9"; }, sent::add));
    assertEquals(0, buys[0]);
    assertEquals(List.of("ERR INVALID", "ERR INVALID", "ERR INVALID"), sent);
  }

  @Test void buyerFailureIsDeliveredAsInvalid() {
    List<String> sent = new ArrayList<>();
    String line = ShopBuyDispatch.complete(ShopBuyDispatch.Caller.RCON, true, "shop", args(), request -> { throw new IllegalStateException("db"); }, sent::add);
    assertEquals("ERR INVALID", line);
    assertEquals(List.of("ERR INVALID"), sent);
  }

  @Test void storedErrorIsDeliveredUnchanged() {
    List<String> sent = new ArrayList<>();
    assertEquals("ERR NOT_FOUND", ShopBuyDispatch.complete(ShopBuyDispatch.Caller.CONSOLE, true, "shop", args(), request -> "ERR NOT_FOUND", sent::add));
    assertEquals(List.of("ERR NOT_FOUND"), sent);
  }

  @Test void pendingPhraseIsNotTheCallerResult() {
    List<String> sent = new ArrayList<>();
    String line = ShopBuyDispatch.complete(ShopBuyDispatch.Caller.RCON, true, "shop", args(), request -> "wird ausgeführt", sent::add);
    assertEquals("ERR INVALID", line);
    assertEquals(List.of("ERR INVALID"), sent);
    assertFalse(sent.get(0).toLowerCase().contains("wird ausgeführt"));
  }

  @Test void playersOffTheServerThreadAreNotMessagedOnTheCallerThread() {
    assertEquals(ShopBuyDispatch.Delivery.PRIMARY_THREAD, ShopBuyDispatch.delivery(ShopBuyDispatch.Caller.PLAYER, false));
    assertEquals(ShopBuyDispatch.Delivery.IMMEDIATE, ShopBuyDispatch.delivery(ShopBuyDispatch.Caller.PLAYER, true));
    assertEquals(ShopBuyDispatch.Delivery.IMMEDIATE, ShopBuyDispatch.delivery(ShopBuyDispatch.Caller.CONSOLE, false));
    assertEquals(ShopBuyDispatch.Delivery.IMMEDIATE, ShopBuyDispatch.delivery(ShopBuyDispatch.Caller.CONSOLE, true));
    assertEquals(ShopBuyDispatch.Delivery.IMMEDIATE, ShopBuyDispatch.delivery(ShopBuyDispatch.Caller.RCON, false));
    assertEquals(ShopBuyDispatch.Delivery.IMMEDIATE, ShopBuyDispatch.delivery(ShopBuyDispatch.Caller.RCON, true));
  }
}
