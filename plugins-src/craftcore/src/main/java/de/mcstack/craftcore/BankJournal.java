package de.mcstack.craftcore;

import java.sql.*;

/** Called inside the shared CraftCore write transaction. */
final class BankJournal {
  interface MovePrice { void apply(String material) throws Exception; }
  static boolean finishSale(Connection c,String id,MovePrice move) throws Exception {
    try(PreparedStatement s=c.prepareStatement("SELECT player,item,cp,status FROM bank_journal WHERE id=? FOR UPDATE")) {
      s.setString(1,id);
      try(ResultSet r=s.executeQuery()) {
        if(!r.next() || !"PENDING".equals(r.getString(4))) return false;
        String player=r.getString(1), item=r.getString(2); long cp=r.getLong(3);
        try(PreparedStatement create=c.prepareStatement("INSERT IGNORE INTO accounts(uuid,balance) VALUES(?,0)")) {create.setString(1,player);create.executeUpdate();}
        long balance; try(PreparedStatement get=c.prepareStatement("SELECT balance FROM accounts WHERE uuid=? FOR UPDATE")) {get.setString(1,player);try(ResultSet account=get.executeQuery()){if(!account.next())throw new SQLException("Konto fehlt");balance=account.getLong(1);}}
        try(PreparedStatement update=c.prepareStatement("UPDATE accounts SET balance=? WHERE uuid=?")) {update.setLong(1,Math.addExact(balance,cp));update.setString(2,player);update.executeUpdate();}
        move.apply(item);
        try(PreparedStatement done=c.prepareStatement("UPDATE bank_journal SET status='DONE' WHERE id=?")) {done.setString(1,id);done.executeUpdate();}
        return true;
      }
    }
  }
}
