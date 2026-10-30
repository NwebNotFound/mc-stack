package de.mcstack.craftcore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** First reply is PENDING. A later call with the same purchase returns the stored OK or ERR. */
final class ShopBuyDispatch {
  enum Caller { PLAYER, CONSOLE, RCON, OTHER }
  @FunctionalInterface interface Stored { String get(RconPurchase.Request request); }
  @FunctionalInterface interface Start { void start(RconPurchase.Request request); }
  static final int MAX_DONE = 256;
  static final long DONE_TTL_MS = 30L * 60L * 1000L;
  private static final Map<String, Done> DONE = new LinkedHashMap<>();
  private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
  private record Done(String line, long expiresAt) {}

  static String cacheId(RconPurchase.Request request) {
    return request.key() + "\n" + request.kind() + "\n" + request.buyer() + "\n" + request.listing() + "\n" + request.qty() + "\n" + request.max();
  }

  static String lookup(String id, long now) {
    synchronized (DONE) {
      Done done = DONE.get(id);
      if (done == null) return null;
      if (done.expiresAt < now) { DONE.remove(id); return null; }
      return done.line;
    }
  }

  static void clearDone() { synchronized (DONE) { DONE.clear(); } }

  static void remember(String id, String line, long now) {
    synchronized (DONE) {
      DONE.entrySet().removeIf(e -> e.getValue().expiresAt < now);
      DONE.remove(id);
      DONE.put(id, new Done(line, now + DONE_TTL_MS));
      while (DONE.size() > MAX_DONE) DONE.remove(DONE.keySet().iterator().next());
    }
  }

  static String stored(RconPurchase.Request request) { return lookup(cacheId(request), System.currentTimeMillis()); }

  static boolean beginId(String id) { return RUNNING.add(id); }

  static boolean begin(RconPurchase.Request request) { return beginId(cacheId(request)); }

  static void finishId(String id, String line) {
    RUNNING.remove(id);
    if (line == null) return;
    String trimmed = line.trim();
    if (trimmed.equals("OK") || trimmed.startsWith("OK ") || trimmed.startsWith("ERR ")) remember(id, trimmed, System.currentTimeMillis());
  }

  static void finish(RconPurchase.Request request, String line) { finishId(cacheId(request), line); }

  static String complete(Caller caller, boolean survival, String command, String[] args, Stored stored, Start start, Consumer<String> send) {
    String line;
    if ((caller != Caller.CONSOLE && caller != Caller.RCON) || !survival) line = "ERR INVALID";
    else {
      RconPurchase.Request request = RconPurchase.parse(command, args);
      if (request == null) line = "ERR INVALID";
      else {
        String known = stored.get(request);
        if (known != null && (known.equals("OK") || known.startsWith("OK ") || known.startsWith("ERR "))) line = known.trim();
        else { start.start(request); line = "PENDING " + request.key(); }
      }
    }
    send.accept(line);
    return line;
  }
}
