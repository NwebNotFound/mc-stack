package de.mcstack.craftcore;
import java.sql.*;
final class AuctionStore {
  static String buy(Connection c,String id,String buyer,long now,int feeBasisPoints)throws SQLException {
    return buy(c,id,buyer,now,feeBasisPoints,null,null);
  }
  static String buy(Connection c,String id,String buyer,long now,int feeBasisPoints,Long expectedPrice,String expectedItem)throws SQLException {
    String seller; long price;
    try(PreparedStatement s=c.prepareStatement("SELECT seller,price,item FROM ah_journal WHERE id=? AND status='OPEN' AND expires_at>? FOR UPDATE")){s.setString(1,id);s.setLong(2,now);try(ResultSet r=s.executeQuery()){if(!r.next())return "Angebot ist nicht mehr verfuegbar.";seller=r.getString(1);price=r.getLong(2);if(expectedPrice!=null && (price!=expectedPrice || !java.util.Objects.equals(expectedItem,r.getString(3))))return "Angebot wurde geaendert. Bitte erneut oeffnen.";}}
    if(seller.equals(buyer))return "Du kannst dein eigenes Angebot nicht kaufen.";
    try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO accounts(uuid,balance) VALUES (?,0),(?,0)")){s.setString(1,buyer);s.setString(2,seller);s.executeUpdate();}
    long a=balance(c,buyer),b=balance(c,seller);if(a<price)return "Nicht genug CP.";
    long fee=AuctionFee.calculate(price,feeBasisPoints);
    Deliveries.fromJournal(c,"ah_journal",id,buyer);
    try(PreparedStatement s=c.prepareStatement("UPDATE ah_journal SET status='DELIVERY',recipient=? WHERE id=? AND status='OPEN'")){s.setString(1,buyer);s.setString(2,id);if(s.executeUpdate()!=1)return "Angebot ist nicht mehr verfuegbar.";}
    try(PreparedStatement s=c.prepareStatement("UPDATE accounts SET balance=? WHERE uuid=?")){s.setLong(1,a-price);s.setString(2,buyer);s.executeUpdate();s.setLong(1,Math.addExact(b,price-fee));s.setString(2,seller);s.executeUpdate();}AuctionFee.record(c,java.util.UUID.randomUUID().toString(),id,buyer,seller,price,fee,now);return null;
  }
  private static long balance(Connection c,String id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT balance FROM accounts WHERE uuid=? FOR UPDATE")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Konto fehlt");return r.getLong(1);}}}
  static boolean cancel(Connection c,String id,String seller)throws SQLException{try(PreparedStatement s=c.prepareStatement("UPDATE ah_journal SET status='DELIVERY',recipient=seller WHERE id=? AND seller=? AND status='OPEN'")){s.setString(1,id);s.setString(2,seller);boolean ok=s.executeUpdate()==1;if(ok)Deliveries.fromJournal(c,"ah_journal",id,seller);return ok;}}
}
