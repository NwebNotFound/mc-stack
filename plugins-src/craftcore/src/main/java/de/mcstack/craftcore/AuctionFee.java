package de.mcstack.craftcore;

import java.sql.*;
import java.math.BigInteger;

final class AuctionFee {
  static long calculate(long gross,int basisPoints) {
    if (gross<0 || basisPoints<0 || basisPoints>10000) throw new IllegalArgumentException("Ungültige Auktionsgebühr");
    return BigInteger.valueOf(gross).multiply(BigInteger.valueOf(basisPoints)).add(BigInteger.valueOf(9999)).divide(BigInteger.valueOf(10000)).longValueExact();
  }
  static void record(Connection c,String purchase,String listing,String buyer,String seller,long gross,long fee,long now)throws SQLException {
    try(PreparedStatement s=c.prepareStatement("INSERT INTO ah_fee_journal(purchase_id,listing_id,buyer,seller,gross,fee,created_at) VALUES(?,?,?,?,?,?,?)")){s.setString(1,purchase);s.setString(2,listing);s.setString(3,buyer);s.setString(4,seller);s.setLong(5,gross);s.setLong(6,fee);s.setLong(7,now);s.executeUpdate();}
  }
}
