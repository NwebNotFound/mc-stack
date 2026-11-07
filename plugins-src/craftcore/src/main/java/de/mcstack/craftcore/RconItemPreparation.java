package de.mcstack.craftcore;

import java.sql.*;
import java.util.Objects;

/** Prepare Bukkit item bytes before acquiring purchase locks; locked reads must match. */
final class RconItemPreparation {
  record Snapshot(String item, int amount) {}

  static Snapshot load(Connection c, RconPurchase.Request request) throws SQLException {
    String table=request.kind().equals("shop")?"shop_journal":"ah_journal";
    try (PreparedStatement s=c.prepareStatement("SELECT item,amount FROM "+table+" WHERE id=?")) {
      s.setString(1,request.listing());
      try (ResultSet r=s.executeQuery()) { return r.next()?new Snapshot(r.getString(1),r.getInt(2)):null; }
    }
  }

  static RconPurchase.Remaining prepare(Snapshot snapshot, int quantity, RconPurchase.Remaining mainThreadEncoder) throws Exception {
    int left=snapshot==null?0:snapshot.amount()-quantity;
    String encoded=left>0?mainThreadEncoder.encode(snapshot.item(),left):null;
    return (item,amount)->{
      // A concurrent edit/purchase invalidates preparation. Throw so the transaction
      // rolls back (including idempotency); the same command can be retried safely.
      if (encoded==null || snapshot==null || !Objects.equals(snapshot.item(),item) || left!=amount)
        throw new SQLException("Listing changed during item preparation; retry purchase");
      return encoded;
    };
  }
}
