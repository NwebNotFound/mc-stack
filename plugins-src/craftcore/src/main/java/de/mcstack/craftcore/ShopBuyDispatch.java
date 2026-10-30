package de.mcstack.craftcore;

import java.util.Locale;
import java.util.function.Consumer;

/** Shop and AH buys answer on the calling command before it returns. */
final class ShopBuyDispatch {
  enum Caller { PLAYER, CONSOLE, RCON, OTHER }
  enum Delivery { IMMEDIATE, PRIMARY_THREAD }
  @FunctionalInterface interface Buyer { String buy(RconPurchase.Request request) throws Exception; }

  static Delivery delivery(Caller caller, boolean commandOnPrimaryThread) {
    return caller == Caller.PLAYER && !commandOnPrimaryThread ? Delivery.PRIMARY_THREAD : Delivery.IMMEDIATE;
  }

  static String complete(Caller caller, boolean survival, String command, String[] args, Buyer buyer, Consumer<String> send) {
    String line = outcome(caller, survival, command, args, buyer);
    send.accept(line);
    return line;
  }

  private static String outcome(Caller caller, boolean survival, String command, String[] args, Buyer buyer) {
    if ((caller != Caller.CONSOLE && caller != Caller.RCON) || !survival) return "ERR INVALID";
    RconPurchase.Request request = RconPurchase.parse(command, args);
    if (request == null) return "ERR INVALID";
    String line;
    try { line = buyer.buy(request); }
    catch (Exception ex) { return "ERR INVALID"; }
    if (line == null) return "ERR INVALID";
    String trimmed = line.trim();
    if (trimmed.isEmpty() || pending(trimmed) || !(trimmed.startsWith("OK ") || trimmed.startsWith("ERR "))) return "ERR INVALID";
    return trimmed;
  }

  private static boolean pending(String line) {
    String low = line.toLowerCase(Locale.ROOT);
    return low.contains("wird ausgeführt") || low.contains("wird ausgefuehrt");
  }
}
