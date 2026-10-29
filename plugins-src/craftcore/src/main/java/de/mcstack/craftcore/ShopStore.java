package de.mcstack.craftcore;

import java.sql.*;

final class ShopStore {
  static boolean publish(Connection c,String id) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("UPDATE shop_journal SET status='OPEN' WHERE id=? AND status='PENDING'")){s.setString(1,id);boolean ok=s.executeUpdate()==1;if(ok)event(c,id,"gelistet",null);return ok;}
  }
  static String buy(Connection c,String id,String buyer) throws SQLException {
    String seller; long price;
    try(PreparedStatement s=c.prepareStatement("SELECT seller,price FROM shop_journal WHERE id=? AND status='OPEN' FOR UPDATE")) {s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())return "Angebot ist nicht mehr verfuegbar.";seller=r.getString(1);price=r.getLong(2);}}
    if(seller.equals(buyer))return "Du kannst dein eigenes Angebot nicht kaufen.";
    try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO accounts(uuid,balance) VALUES (?,0),(?,0)")){s.setString(1,buyer);s.setString(2,seller);s.executeUpdate();}
    long a=balance(c,buyer), b=balance(c,seller); if(a<price)return "Nicht genug CP.";
    long credited=Math.addExact(b,price);
    Deliveries.fromJournal(c,"shop_journal",id,buyer);
    try(PreparedStatement s=c.prepareStatement("UPDATE shop_journal SET status='DELIVERY', recipient=? WHERE id=? AND status='OPEN'")){s.setString(1,buyer);s.setString(2,id);if(s.executeUpdate()!=1)return "Angebot ist nicht mehr verfuegbar.";}
    try(PreparedStatement s=c.prepareStatement("UPDATE accounts SET balance=? WHERE uuid=?")){s.setLong(1,a-price);s.setString(2,buyer);s.executeUpdate();s.setLong(1,credited);s.setString(2,seller);s.executeUpdate();}
    try(PreparedStatement s=c.prepareStatement("INSERT INTO shop_notifications(id,seller,listing_id,buyer,price,delivered) VALUES(?,?,?,?,?,FALSE)")){s.setString(1,java.util.UUID.randomUUID().toString());s.setString(2,seller);s.setString(3,id);s.setString(4,buyer);s.setLong(5,price);s.executeUpdate();}
    event(c,id,"verkauft",buyer);
    return null;
  }
  static void event(Connection c,String id,String type,String actor)throws SQLException {try(PreparedStatement s=c.prepareStatement("INSERT INTO shop_events(id,listing_id,event_type,seller,seller_name,price,actor,created_at) SELECT ?,id,?,seller,seller_name,price,?,CURRENT_TIMESTAMP FROM shop_journal WHERE id=?")){s.setString(1,java.util.UUID.randomUUID().toString());s.setString(2,type);s.setString(3,actor);s.setString(4,id);s.executeUpdate();}}
  private static long balance(Connection c,String id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT balance FROM accounts WHERE uuid=? FOR UPDATE")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Konto fehlt");return r.getLong(1);}}}
  static boolean cancel(Connection c,String id,String seller)throws SQLException {try(PreparedStatement s=c.prepareStatement("UPDATE shop_journal SET status='DELIVERY', recipient=seller WHERE id=? AND seller=? AND status='OPEN'")){s.setString(1,id);s.setString(2,seller);boolean ok=s.executeUpdate()==1;if(ok){Deliveries.fromJournal(c,"shop_journal",id,seller);event(c,id,"zurueckgezogen",seller);}return ok;}}
}
