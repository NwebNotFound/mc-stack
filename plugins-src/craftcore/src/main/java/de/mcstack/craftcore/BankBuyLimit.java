package de.mcstack.craftcore;

import java.sql.*;

/** The caller holds the transaction until the purchase is completed or rolled back. */
final class BankBuyLimit {
  static boolean reserve(Connection c, String owner, String item, String day, int limit) throws SQLException {
    if(limit < 1) throw new IllegalArgumentException("market.daily-buy-limit muss positiv sein");
    try(PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO bank_buy_daily(owner,item,day_key,amount) VALUES(?,?,?,0)")) {
      s.setString(1,owner); s.setString(2,item); s.setString(3,day); s.executeUpdate();
    }
    try(PreparedStatement s=c.prepareStatement("UPDATE bank_buy_daily SET amount=amount+1 WHERE owner=? AND item=? AND day_key=? AND amount<?")) {
      s.setString(1,owner); s.setString(2,item); s.setString(3,day); s.setInt(4,limit); return s.executeUpdate()==1;
    }
  }
  private BankBuyLimit() {}
}
