package de.mcstack.craftcore;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Caller owns the transaction, including the idempotency record. */
final class RconPurchase {
  interface Remaining { String encode(String item, int quantity) throws Exception; }
  record Request(String kind, String buyer, String listing, int qty, long max, String key) {}
  static Request parse(String kind, String[] args) {
    try {
      if (!(kind.equals("shop") || kind.equals("ah")) || args.length != 6 || !args[0].equalsIgnoreCase("buy")) return null;
      String buyer = canonicalUuid(args[1]), listing = canonicalUuid(args[2]);
      if (!args[3].matches("[0-9]+") || !args[4].matches("[0-9]+") || !args[5].matches("[A-Za-z0-9_-]{1,64}")) return null;
      int qty = Integer.parseInt(args[3]); long max = Long.parseLong(args[4]);
      return qty > 0 ? new Request(kind,buyer,listing,qty,max,args[5]) : null;
    } catch (IllegalArgumentException e) { return null; }
  }
  private static String canonicalUuid(String value) {
    UUID id=UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id.toString();
  }
  static String buy(Connection c, Request req, long now, Remaining remaining, int feeBasisPoints) throws Exception {
    byte[] key=req.key().getBytes(StandardCharsets.US_ASCII);
    int inserted;
    try (PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO rcon_purchases(idem_key,kind,buyer,listing_id,qty,max_price,response,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
      s.setBytes(1,key); s.setString(2,req.kind()); s.setString(3,req.buyer()); s.setString(4,req.listing()); s.setInt(5,req.qty()); s.setLong(6,req.max()); s.setString(7,""); s.setLong(8,now); inserted=s.executeUpdate();
    }
    if (inserted==0) {
      try(PreparedStatement s=c.prepareStatement("SELECT kind,buyer,listing_id,qty,max_price,response FROM rcon_purchases WHERE idem_key=? FOR UPDATE")) {
        s.setBytes(1,key); try(ResultSet r=s.executeQuery()) { if(!r.next()) throw new SQLException("Idempotency record missing");
          return r.getString(1).equals(req.kind()) && r.getString(2).equals(req.buyer()) && r.getString(3).equals(req.listing()) && r.getInt(4)==req.qty() && r.getLong(5)==req.max() ? r.getString(6) : "ERR DUPLICATE";
        }
      }
    }
    String response=execute(c,req,now,remaining,feeBasisPoints);
    try(PreparedStatement s=c.prepareStatement("UPDATE rcon_purchases SET response=? WHERE idem_key=?")){s.setString(1,response);s.setBytes(2,key);s.executeUpdate();}
    return response;
  }
  private static String execute(Connection c,Request req,long now,Remaining remaining,int feeBasisPoints)throws Exception {
    String table=req.kind().equals("shop")?"shop_journal":"ah_journal";
    String seller,item,status; long price,expires=Long.MAX_VALUE; int available;
    try(PreparedStatement s=c.prepareStatement("SELECT seller,price,item,amount,status"+(req.kind().equals("ah")?",expires_at":"")+" FROM "+table+" WHERE id=? FOR UPDATE")) {
      s.setString(1,req.listing()); try(ResultSet r=s.executeQuery()) {
        if(!r.next()) return "ERR NOT_FOUND";
        seller=r.getString(1);price=r.getLong(2);item=r.getString(3);available=r.getInt(4);status=r.getString(5);if(req.kind().equals("ah"))expires=r.getLong(6);
      }
    }
    if ("SOLD".equals(status)) return "ERR SOLD_OUT";
    if (!"OPEN".equals(status) || expires<=now) return "ERR NOT_FOUND";
    if (seller.equals(req.buyer())) return "ERR SELF_BUY";
    if (req.qty()>available) return "ERR SOLD_OUT";
    long total;
    try { total=UnitPrice.total(UnitPrice.fromTotal(price,available),req.qty()); } catch(ArithmeticException | IllegalArgumentException e) { return "ERR INVALID"; }
    if(total>req.max()) return "ERR PRICE_CHANGED";
    if (TestRun.fakeUuid(req.buyer()) != null && TestRun.fakeUuid(seller) == null) return "ERR INVALID";
    String rest=req.qty()==available?item:remaining.encode(item,available-req.qty());
    String result=PartialPurchase.buy(c,table,req.listing(),req.buyer(),UUID.randomUUID().toString(),price,item,available,req.qty(),rest,now,feeBasisPoints);
    if(result!=null) {
      if(result.equals("Nicht genug CP.")) return "ERR NO_FUNDS";
      throw new SQLException("Unexpected purchase result: "+result);
    }
    return "OK "+req.qty()+" "+total+" "+(available-req.qty());
  }
}
