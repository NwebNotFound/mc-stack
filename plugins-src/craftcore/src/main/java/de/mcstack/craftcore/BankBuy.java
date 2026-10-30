package de.mcstack.craftcore;

import java.sql.*;
import java.util.Locale;
import java.util.UUID;

/**
 * Bank purchase from the web/Discord order queue (order type bank_buy).
 * Relay sends {@code shop buy bank <mc_uuid> <item> <amount> <request_id>}; the reply works like shop buy (PENDING, then OK/ERR).
 * Same checks as a bank purchase in the GUI: lock_buy/unbuyable, daily buy limit, balance, price at execution
 * (shared price factor, moves per unit like the GUI). Charged at most once per request_id (bank_buy_orders).
 * Result goes to order_fulfillment: ready (item in /abholen, offline pickup) or failed. Caller owns the transaction.
 */
final class BankBuy {
  record Request(String mcUuid, String item, int amount, String requestId) {}
  interface Env {
    /** null = allowed, otherwise UNKNOWN or LOCKED. */
    String blocked(Connection c, String item) throws Exception;
    int maxStack(String item);
    /** Current unit price in cents (no move). */
    long unitPrice(Connection c, String item) throws Exception;
    /** Price move after one sold unit (same as GUI purchase). */
    void moved(Connection c, String item) throws Exception;
    /** Reserve one unit of the daily buy limit; false = limit reached. */
    boolean reserve(Connection c, String mcUuid, String item) throws Exception;
    long balance(Connection c, String mcUuid) throws Exception;
    void debit(Connection c, String mcUuid, long cents) throws Exception;
    void deliver(Connection c, String deliveryId, String mcUuid, String item, int amount) throws Exception;
    default void stats(Connection c, String item, int amount, long cents) throws Exception {}
  }

  static Request parse(String[] args) {
    // shop buy bank <uuid> <item> <amount> <request_id>
    try {
      if(args.length!=6 || !args[0].equalsIgnoreCase("buy") || !args[1].equalsIgnoreCase("bank")) return null;
      UUID id=UUID.fromString(args[2]); if(!id.toString().equals(args[2])) return null;
      String item=args[3].toUpperCase(Locale.ROOT); if(item.startsWith("MINECRAFT:")) item=item.substring(10);
      if(!item.matches("[A-Z0-9_]{1,64}") || !args[4].matches("[0-9]{1,5}") || !args[5].matches("[A-Za-z0-9_-]{1,64}")) return null;
      int amount=Integer.parseInt(args[4]);
      return amount>0 ? new Request(id.toString(),item,amount,args[5]) : null;
    } catch(IllegalArgumentException e) { return null; }
  }
  static String cacheId(Request r) { return "bank\n"+r.requestId(); }

  static void migrate(Connection c) throws SQLException {
    try(Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS bank_buy_orders (request_id VARCHAR(64) NOT NULL PRIMARY KEY, mc_uuid CHAR(36) NOT NULL, item VARCHAR(64) NOT NULL, amount INT NOT NULL, response VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL)");
    }
  }

  static String execute(Connection c, Request req, long now, Env env) throws Exception {
    int inserted;
    try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO bank_buy_orders(request_id,mc_uuid,item,amount,response,created_at) VALUES(?,?,?,?,'',?)")) {
      s.setString(1,req.requestId()); s.setString(2,req.mcUuid()); s.setString(3,req.item()); s.setInt(4,req.amount()); s.setLong(5,now); inserted=s.executeUpdate();
    }
    if(inserted==0) {
      try(PreparedStatement s=c.prepareStatement("SELECT mc_uuid,item,amount,response FROM bank_buy_orders WHERE request_id=? FOR UPDATE")) {
        s.setString(1,req.requestId()); try(ResultSet r=s.executeQuery()) {
          if(!r.next()) throw new SQLException("bank_buy_orders: Eintrag fehlt");
          boolean same=r.getString(1).equals(req.mcUuid()) && r.getString(2).equals(req.item()) && r.getInt(3)==req.amount();
          if(!same) return "ERR DUPLICATE";
          if(!r.getString(4).isEmpty()) return r.getString(4);
        }
      }
    }
    Savepoint sp=c.setSavepoint();
    String response=run(c,req,env);
    if(!response.startsWith("OK")) c.rollback(sp);
    try(PreparedStatement s=c.prepareStatement("UPDATE bank_buy_orders SET response=? WHERE request_id=?")) { s.setString(1,response); s.setString(2,req.requestId()); s.executeUpdate(); }
    if(!response.startsWith("OK")) OrderFulfillment.mark(c,req.requestId(),"failed");
    return response;
  }

  private static String run(Connection c, Request req, Env env) throws Exception {
    String blocked=env.blocked(c,req.item());
    if(blocked!=null) return "ERR "+blocked;
    if(req.amount()>env.maxStack(req.item())) return "ERR INVALID";
    long total=0;
    for(int i=0;i<req.amount();i++) {
      if(!env.reserve(c,req.mcUuid(),req.item())) return "ERR LIMIT";
      total=Math.addExact(total,env.unitPrice(c,req.item()));
      env.moved(c,req.item());
    }
    if(env.balance(c,req.mcUuid())<total) return "ERR NO_FUNDS";
    env.debit(c,req.mcUuid(),total);
    String delivery=UUID.randomUUID().toString();
    env.deliver(c,delivery,req.mcUuid(),req.item(),req.amount());
    try(PreparedStatement q=c.prepareStatement("INSERT INTO order_fulfillment(request_id,delivery_id,status,updated_at) VALUES(?,?,'ready',CURRENT_TIMESTAMP)")) {
      q.setString(1,req.requestId()); q.setString(2,delivery); q.executeUpdate();
    }
    env.stats(c,req.item(),req.amount(),total);
    return "OK "+req.amount()+" "+total;
  }
  private BankBuy() {}
}
