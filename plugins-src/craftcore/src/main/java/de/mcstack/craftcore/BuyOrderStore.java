package de.mcstack.craftcore;

import java.sql.*;

/** Buy-order operations; caller must commit or roll back the whole transaction. */
final class BuyOrderStore {
  private BuyOrderStore() {}

  static boolean create(Connection c, String id, String buyer, String name, String item, int amount, long unitPrice) throws SQLException {
    long total = UnitPrice.total(unitPrice, amount);
    if (!EconomyStore.change(c, buyer, -total)) return false;
    try (PreparedStatement q = c.prepareStatement("INSERT INTO buy_orders(id,buyer,buyer_name,item,remaining,unit_price,status) VALUES(?,?,?,?,?,?,'OPEN')")) {
      q.setString(1,id); q.setString(2,buyer); q.setString(3,name); q.setString(4,item); q.setInt(5,amount); q.setLong(6,unitPrice); q.executeUpdate();
    }
    return true;
  }

  static boolean cancel(Connection c, String id, String buyer) throws SQLException {
    int remaining; long price;
    try (PreparedStatement q=c.prepareStatement("SELECT remaining,unit_price FROM buy_orders WHERE id=? AND buyer=? AND status='OPEN' FOR UPDATE")) {
      q.setString(1,id); q.setString(2,buyer);
      try(ResultSet r=q.executeQuery()){if(!r.next())return false; remaining=r.getInt(1); price=r.getLong(2);}
    }
    // Sales take the same order row lock, so remaining is final here. A partially filled
    // order can be withdrawn too: only the unfilled rest (remaining * unit_price) is refunded.
    EconomyStore.change(c,buyer,UnitPrice.total(price,remaining));
    try(PreparedStatement q=c.prepareStatement("UPDATE buy_orders SET status='CANCELLED',remaining=0 WHERE id=?")){q.setString(1,id);q.executeUpdate();}
    return true;
  }

  // itemData must describe exactly the items taken from the seller's inventory.
  static boolean sell(Connection c,String actionId,String id,String seller,int amount,String expectedItem,String itemData) throws SQLException {
    if(amount<1)throw new IllegalArgumentException("Invalid quantity");
    String buyer, item; int remaining; long price;
    try(PreparedStatement q=c.prepareStatement("SELECT buyer,item,remaining,unit_price FROM buy_orders WHERE id=? AND status='OPEN' FOR UPDATE")) {
      q.setString(1,id);try(ResultSet r=q.executeQuery()){if(!r.next())return false;buyer=r.getString(1);item=r.getString(2);remaining=r.getInt(3);price=r.getLong(4);}
    }
    if(buyer.equals(seller)||amount>remaining||!item.equals(expectedItem))return false;
    try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO buy_order_sales(id,order_id,seller,amount) VALUES(?,?,?,?)")) {
      q.setString(1,actionId);q.setString(2,id);q.setString(3,seller);q.setInt(4,amount);if(q.executeUpdate()!=1)return false;
    }
    long total=UnitPrice.total(price,amount);
    EconomyStore.change(c,seller,total);
    try(PreparedStatement q=c.prepareStatement("INSERT INTO pending_deliveries(id,player_uuid,item_nbt,amount,source,status) VALUES(?,?,?,?,?,'offen')")) {
      q.setString(1,actionId);q.setString(2,buyer);q.setString(3,itemData);q.setInt(4,amount);q.setString(5,"buy_order");q.executeUpdate();
    }
    try(PreparedStatement q=c.prepareStatement("UPDATE buy_orders SET remaining=?,status=? WHERE id=?")) {
      q.setInt(1,remaining-amount);q.setString(2,remaining==amount?"FILLED":"OPEN");q.setString(3,id);q.executeUpdate();
    }
    return true;
  }
}
