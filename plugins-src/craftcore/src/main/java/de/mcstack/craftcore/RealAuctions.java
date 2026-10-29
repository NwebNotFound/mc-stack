package de.mcstack.craftcore;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All methods operate in the caller's transaction. */
final class RealAuctions {
 record Request(long auction,String bidder,long amount,String key,String source) {}
 static Request parse(String[] a) {
  try {
   if(a.length<5||a.length>6||!a[0].equalsIgnoreCase("bid")||!a[1].matches("[0-9]+")||!a[3].matches("[0-9]+")||!a[4].matches("[A-Za-z0-9_-]{1,64}"))return null;
   long id=Long.parseLong(a[1]), amount=Long.parseLong(a[3]); String uuid=UUID.fromString(a[2]).toString(),source=a.length==6?a[5].toLowerCase(Locale.ROOT):"ingame";
   if(!uuid.equalsIgnoreCase(a[2])||id<1||amount<1||!Set.of("ingame","discord","web").contains(source))return null;
   return new Request(id,uuid,amount,a[4],source);
  } catch(IllegalArgumentException ex){return null;}
 }
 static void schema(Connection c)throws SQLException {
  try(Statement s=c.createStatement()){
   s.execute("CREATE TABLE IF NOT EXISTS auctions (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,seller CHAR(36) NOT NULL,item LONGTEXT NOT NULL,material VARCHAR(64) NOT NULL,amount INT NOT NULL,min_price BIGINT NOT NULL,current_bid BIGINT NULL,current_bidder CHAR(36) NULL,bid_count INT NOT NULL DEFAULT 0,ends_at BIGINT NOT NULL,status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL,source_last_bid VARCHAR(16) NULL,INDEX auction_end(status,ends_at))");
   s.execute("CREATE TABLE IF NOT EXISTS auction_bids (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,auction_id BIGINT NOT NULL,bidder CHAR(36) NOT NULL,amount BIGINT NOT NULL,created_at BIGINT NOT NULL,source VARCHAR(16) NOT NULL,INDEX bid_auction(auction_id,id))");
   s.execute("CREATE TABLE IF NOT EXISTS auction_escrow (auction_id BIGINT PRIMARY KEY,bidder CHAR(36) NOT NULL,amount BIGINT NOT NULL)");
   s.execute("CREATE TABLE IF NOT EXISTS auction_requests (request_id VARBINARY(64) PRIMARY KEY,auction_id BIGINT NOT NULL,bidder CHAR(36) NOT NULL,amount BIGINT NOT NULL,source VARCHAR(16) NOT NULL,response VARCHAR(128) NOT NULL)");
   s.execute("CREATE TABLE IF NOT EXISTS auction_alerts (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,auction_id BIGINT NOT NULL,bidder CHAR(36) NOT NULL,reason VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL)");
   s.execute("CREATE OR REPLACE VIEW v_auctions AS SELECT id,material AS item,amount AS menge,seller,min_price,current_bid,current_bidder,bid_count,ends_at,status,created_at,source_last_bid,CASE WHEN current_bid IS NULL THEN min_price ELSE current_bid+GREATEST(100,CEILING(current_bid/20)) END AS min_next_bid FROM auctions");
   s.execute("CREATE OR REPLACE VIEW v_auction_bids AS SELECT id,auction_id,bidder,amount,created_at,source FROM auction_bids");
  }
 }
 static String bid(Connection c,Request req,long now)throws SQLException {
  byte[] key=req.key().getBytes(StandardCharsets.US_ASCII); int added;
  try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO auction_requests VALUES(?,?,?,?,?,'')")){s.setBytes(1,key);s.setLong(2,req.auction());s.setString(3,req.bidder());s.setLong(4,req.amount());s.setString(5,req.source());added=s.executeUpdate();}
  if(added==0){try(PreparedStatement s=c.prepareStatement("SELECT auction_id,bidder,amount,source,response FROM auction_requests WHERE request_id=? FOR UPDATE")){s.setBytes(1,key);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("request missing");return r.getLong(1)==req.auction()&&r.getString(2).equals(req.bidder())&&r.getLong(3)==req.amount()&&r.getString(4).equals(req.source())?r.getString(5):"ERR DUPLICATE";}}}
  String result=execute(c,req,now);
  try(PreparedStatement s=c.prepareStatement("UPDATE auction_requests SET response=? WHERE request_id=?")){s.setString(1,result);s.setBytes(2,key);s.executeUpdate();}return result;
 }
 private static String execute(Connection c,Request req,long now)throws SQLException {
  String seller,old;long min,current,end;String status;
  try(PreparedStatement s=c.prepareStatement("SELECT seller,min_price,current_bid,current_bidder,ends_at,status FROM auctions WHERE id=? FOR UPDATE")){s.setLong(1,req.auction());try(ResultSet r=s.executeQuery()){if(!r.next())return "ERR NOT_FOUND";seller=r.getString(1);min=r.getLong(2);current=r.getLong(3);old=r.getString(4);end=r.getLong(5);status=r.getString(6);}}
  if(!status.equals("OPEN")||end<=now)return "ERR NOT_FOUND";
  if(seller.equals(req.bidder())){alert(c,req,now,"SELF_BID");return "ERR SELF_BID";}
  // Lock both link rows in deterministic order, preventing cclink updates during the check.
  String first=seller.compareTo(req.bidder())<0?seller:req.bidder(),second=first.equals(seller)?req.bidder():seller;
  String a=DiscordRevenue.owner(c,first,true),b=DiscordRevenue.owner(c,second,true);
  if(a.startsWith("d:")&&a.equals(b)){alert(c,req,now,"LINKED_ACCOUNT");return "ERR LINKED_ACCOUNT";}
  long next;try{next=old==null?min:AuctionRules.minimumNext(current);}catch(ArithmeticException ex){return "ERR INVALID";}
  if(req.amount()<next)return "ERR BID_TOO_LOW";
  // Existing escrow is returned before charging, including for the same bidder.
  TreeSet<String> accounts=new TreeSet<>();accounts.add(req.bidder());if(old!=null)accounts.add(old);
  for(String id:accounts){try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO accounts(uuid,balance) VALUES(?,0)")){s.setString(1,id);s.executeUpdate();}try(PreparedStatement s=c.prepareStatement("SELECT balance FROM accounts WHERE uuid=? FOR UPDATE")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("account missing");}}}
  long balance;try(PreparedStatement s=c.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")){s.setString(1,req.bidder());try(ResultSet r=s.executeQuery()){r.next();balance=r.getLong(1);}}
  if(old!=null&&old.equals(req.bidder()))try{balance=Math.addExact(balance,current);}catch(ArithmeticException ex){throw new SQLException("balance overflow",ex);}
  if(balance<req.amount())return "ERR NO_FUNDS";
  if(old!=null){try(PreparedStatement s=c.prepareStatement("UPDATE accounts SET balance=balance+? WHERE uuid=?")){s.setLong(1,current);s.setString(2,old);s.executeUpdate();}try(PreparedStatement s=c.prepareStatement("DELETE FROM auction_escrow WHERE auction_id=? AND bidder=? AND amount=?")){s.setLong(1,req.auction());s.setString(2,old);s.setLong(3,current);if(s.executeUpdate()!=1)throw new SQLException("escrow mismatch");}}
  try(PreparedStatement s=c.prepareStatement("UPDATE accounts SET balance=balance-? WHERE uuid=?")){s.setLong(1,req.amount());s.setString(2,req.bidder());s.executeUpdate();}
  try(PreparedStatement s=c.prepareStatement("INSERT INTO auction_escrow VALUES(?,?,?)")){s.setLong(1,req.auction());s.setString(2,req.bidder());s.setLong(3,req.amount());s.executeUpdate();}
  try(PreparedStatement s=c.prepareStatement("INSERT INTO auction_bids(auction_id,bidder,amount,created_at,source) VALUES(?,?,?,?,?)")){s.setLong(1,req.auction());s.setString(2,req.bidder());s.setLong(3,req.amount());s.setLong(4,now);s.setString(5,req.source());s.executeUpdate();}
  long newEnd=AuctionRules.extendedEnd(now,end);
  try(PreparedStatement s=c.prepareStatement("UPDATE auctions SET current_bid=?,current_bidder=?,bid_count=bid_count+1,ends_at=?,source_last_bid=? WHERE id=?")){s.setLong(1,req.amount());s.setString(2,req.bidder());s.setLong(3,newEnd);s.setString(4,req.source());s.setLong(5,req.auction());s.executeUpdate();}
  return "OK "+req.auction()+" "+req.amount()+" "+newEnd;
 }
 private static void alert(Connection c,Request r,long now,String reason)throws SQLException {try(PreparedStatement s=c.prepareStatement("INSERT INTO auction_alerts(auction_id,bidder,reason,created_at) VALUES(?,?,?,?)")){s.setLong(1,r.auction());s.setString(2,r.bidder());s.setString(3,reason);s.setLong(4,now);s.executeUpdate();}}
 static void settle(Connection c,long id,long now)throws SQLException {
  String seller,winner,item;long gross;int amount;
  try(PreparedStatement s=c.prepareStatement("SELECT seller,current_bidder,current_bid,item,amount FROM auctions WHERE id=? AND status='OPEN' AND ends_at<=? FOR UPDATE")){s.setLong(1,id);s.setLong(2,now);try(ResultSet r=s.executeQuery()){if(!r.next())return;seller=r.getString(1);winner=r.getString(2);gross=r.getLong(3);item=r.getString(4);amount=r.getInt(5);}}
  String recipient=winner==null?seller:winner;
  try(PreparedStatement s=c.prepareStatement("INSERT INTO pending_deliveries(id,player_uuid,item_nbt,amount,source,status) VALUES(?,?,?,?,?,'offen')")){s.setString(1,UUID.randomUUID().toString());s.setString(2,recipient);s.setString(3,item);s.setInt(4,amount);s.setString(5,"auction");s.executeUpdate();}
  if(winner!=null){try(PreparedStatement s=c.prepareStatement("DELETE FROM auction_escrow WHERE auction_id=? AND bidder=? AND amount=?")){s.setLong(1,id);s.setString(2,winner);s.setLong(3,gross);if(s.executeUpdate()!=1)throw new SQLException("escrow mismatch");}try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO accounts(uuid,balance) VALUES(?,0)")){s.setString(1,seller);s.executeUpdate();}try(PreparedStatement s=c.prepareStatement("UPDATE accounts SET balance=balance+? WHERE uuid=?")){s.setLong(1,gross-AuctionFee.calculate(gross,300));s.setString(2,seller);s.executeUpdate();}}
  try(PreparedStatement s=c.prepareStatement("UPDATE auctions SET status=? WHERE id=?")){s.setString(1,winner==null?"EXPIRED":"SOLD");s.setLong(2,id);s.executeUpdate();}
 }
 private RealAuctions(){}
}
