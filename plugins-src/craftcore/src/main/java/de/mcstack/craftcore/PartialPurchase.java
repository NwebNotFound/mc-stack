package de.mcstack.craftcore;

import java.sql.*;

/** All operations run in the caller's transaction. */
final class PartialPurchase {
  static String buy(Connection c, String table, String id, String buyer, String purchaseId,
                    long expectedPrice, String expectedItem, int expectedAmount, int quantity,
                    String remainingItem, long now, int feeBasisPoints) throws SQLException {
    if (!table.equals("shop_journal") && !table.equals("ah_journal")) throw new SQLException("Invalid journal");
    if (exists(c, purchaseId)) return "Dieser Kauf wurde bereits bearbeitet.";
    String seller, item, material; long price; int available;
    String sql="SELECT seller,price,item,amount,material FROM "+table+" WHERE id=? AND status='OPEN'"+(table.equals("ah_journal")?" AND expires_at>?":"")+" FOR UPDATE";
    try (PreparedStatement q=c.prepareStatement(sql)) {
      q.setString(1,id); if(table.equals("ah_journal")) q.setLong(2,now);
      try(ResultSet r=q.executeQuery()) {
        if(!r.next()) return exists(c,purchaseId)?"Dieser Kauf wurde bereits bearbeitet.":"Angebot ist nicht mehr verfügbar.";
        seller=r.getString(1); price=r.getLong(2); item=r.getString(3); available=r.getInt(4); material=r.getString(5);
      }
    }
    if(exists(c,purchaseId)) return "Dieser Kauf wurde bereits bearbeitet.";
    if(price!=expectedPrice || available!=expectedAmount || !item.equals(expectedItem)) return "Preis oder Bestand hat sich geändert. Bitte erneut öffnen.";
    if(quantity<1 || quantity>available) return "Bestand hat sich geändert.";
    if(seller.equals(buyer)) return "Du kannst dein eigenes Angebot nicht kaufen.";
    long total=UnitPrice.total(UnitPrice.fromTotal(price,available),quantity);
    try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO accounts(uuid,balance) VALUES (?,0),(?,0)")){q.setString(1,buyer);q.setString(2,seller);q.executeUpdate();}
    String first=buyer.compareTo(seller)<0?buyer:seller, second=first.equals(buyer)?seller:buyer;
    long firstBalance=balance(c,first), secondBalance=balance(c,second);
    long buyerBalance=first.equals(buyer)?firstBalance:secondBalance, sellerBalance=first.equals(seller)?firstBalance:secondBalance;
    if(buyerBalance<total) return "Nicht genug CP.";
    long fee=table.equals("ah_journal") ? AuctionFee.calculate(total,feeBasisPoints) : 0;
    long credited=Math.addExact(sellerBalance,total-fee);
    try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO purchase_ids(id) VALUES (?)")){q.setString(1,purchaseId);if(q.executeUpdate()==0)return "Dieser Kauf wurde bereits bearbeitet.";}
    try(PreparedStatement q=c.prepareStatement("INSERT INTO pending_deliveries(id,player_uuid,item_nbt,amount,source,status) VALUES(?,?,?,?,?,'offen')")){q.setString(1,purchaseId);q.setString(2,buyer);q.setString(3,item);q.setInt(4,quantity);q.setString(5,table);q.executeUpdate();}
    int rest=available-quantity;
    try(PreparedStatement q=c.prepareStatement("UPDATE "+table+" SET item=?,amount=?,price=?,status=? WHERE id=?")){q.setString(1,rest==0?item:remainingItem);q.setInt(2,rest);q.setLong(3,rest==0?price:UnitPrice.total(UnitPrice.fromTotal(price,available),rest));q.setString(4,rest==0?"SOLD":"OPEN");q.setString(5,id);q.executeUpdate();}
    try(PreparedStatement q=c.prepareStatement("UPDATE accounts SET balance=? WHERE uuid=?")){q.setLong(1,buyerBalance-total);q.setString(2,buyer);q.executeUpdate();q.setLong(1,credited);q.setString(2,seller);q.executeUpdate();}
    SaleNoticeStore.record(c,purchaseId,seller,material,quantity,total-fee,now);
    WebsiteStats.event(c,material,quantity,total,table.equals("ah_journal")?"ah":"shop");
    if(table.equals("ah_journal")) AuctionFee.record(c,purchaseId,id,buyer,seller,total,fee,now);
    if(table.equals("shop_journal")) ShopStore.event(c,id,"verkauft",buyer);
    return null;
  }
  private static boolean exists(Connection c,String id)throws SQLException{try(PreparedStatement q=c.prepareStatement("SELECT id FROM purchase_ids WHERE id=?")){q.setString(1,id);try(ResultSet r=q.executeQuery()){return r.next();}}}
  private static long balance(Connection c,String id)throws SQLException{try(PreparedStatement q=c.prepareStatement("SELECT balance FROM accounts WHERE uuid=? FOR UPDATE")){q.setString(1,id);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Konto fehlt");return r.getLong(1);}}}
}
