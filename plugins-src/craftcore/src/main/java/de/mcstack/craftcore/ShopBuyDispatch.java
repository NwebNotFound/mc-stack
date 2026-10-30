package de.mcstack.craftcore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** First reply is PENDING. A later call with the same purchase returns the stored OK or ERR. */
final class ShopBuyDispatch {
  enum Caller { PLAYER, CONSOLE, RCON, OTHER }
  @FunctionalInterface interface Stored { String get(RconPurchase.Request request); }
  @FunctionalInterface interface Start { void start(RconPurchase.Request request); }
  private static final ConcurrentHashMap<String, String> DONE = new ConcurrentHashMap<>();
  private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

  static String cacheId(RconPurchase.Request request) {
    return request.key() + "\n" + request.kind() + "\n" + request.buyer() + "\n" + request.listing() + "\n" + request.qty() + "\n" + request.max();
  }

  static String stored(RconPurchase.Request request) { return DONE.get(cacheId(request)); }

  static boolean begin(RconPurchase.Request request) { return RUNNING.add(cacheId(request)); }

  static void finish(RconPurchase.Request request, String line) {
    RUNNING.remove(cacheId(request));
    if (line == null) return;
    String trimmed = line.trim();
    if (trimmed.startsWith("OK ") || trimmed.startsWith("ERR ")) DONE.put(cacheId(request), trimmed);
  }

  static String complete(Caller caller, boolean survival, String command, String[] args, Stored stored, Start start, Consumer<String> send) {
    String line;
    if ((caller != Caller.CONSOLE && caller != Caller.RCON) || !survival) line = "ERR INVALID";
    else {
      RconPurchase.Request request = RconPurchase.parse(command, args);
      if (request == null) line = "ERR INVALID";
      else {
        String known = stored.get(request);
        if (known != null && (known.startsWith("OK ") || known.startsWith("ERR "))) line = known.trim();
        else { start.start(request); line = "PENDING " + request.key(); }
      }
    }
    send.accept(line);
    return line;
  }
}
