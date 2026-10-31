package de.mcstack.craftcore;

import java.sql.*;
import java.time.Duration;

final class SellTransaction {
  static final String DDL = "CREATE TABLE IF NOT EXISTS sell_txn (idem_key VARCHAR(64) PRIMARY KEY, status VARCHAR(16) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB";
  static final String PENDING_DDL = "CREATE TABLE IF NOT EXISTS sell_pending (idem_key VARCHAR(64) PRIMARY KEY, player_uuid CHAR(36) NOT NULL, item_nbt LONGTEXT NOT NULL, amount INT NOT NULL, cp BIGINT NOT NULL, resolution VARCHAR(16) NOT NULL DEFAULT 'PENDING', status VARCHAR(16) NOT NULL DEFAULT 'PENDING', created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB";
  static final String PENDING_MIGRATION = "ALTER TABLE sell_pending ADD COLUMN IF NOT EXISTS resolution VARCHAR(16) NOT NULL DEFAULT 'PENDING'";
  static final String STATUS_MIGRATION = "ALTER TABLE sell_pending ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'PENDING'";
  static void refundOwed(Connection c, String key, String player, String nbt, int amount) throws SQLException {
    try (PreparedStatement q=c.prepareStatement("INSERT INTO sell_pending(idem_key,player_uuid,item_nbt,amount,cp,resolution,status) VALUES(?,?,?,?,0,'NOT_COMMITTED','REFUND_OWED') ON DUPLICATE KEY UPDATE status='REFUND_OWED', resolution='NOT_COMMITTED'")) {
      q.setString(1,key); q.setString(2,player); q.setString(3,nbt); q.setInt(4,amount); q.executeUpdate();
    }
  }
  static int queueRefunds(Connection c, String player) throws SQLException {
    int count=0;
    try (PreparedStatement q=c.prepareStatement("SELECT idem_key,item_nbt,amount FROM sell_pending WHERE player_uuid=? AND status='REFUND_OWED' FOR UPDATE")) {
      q.setString(1,player);
      try (ResultSet r=q.executeQuery()) { while(r.next()) {
        try (PreparedStatement insert=c.prepareStatement("INSERT INTO pending_deliveries(id,player_uuid,item_nbt,amount,source,status) VALUES(?,?,?,?,?, 'offen')")) {
          insert.setString(1,r.getString(1)); insert.setString(2,player); insert.setString(3,r.getString(2)); insert.setInt(4,r.getInt(3)); insert.setString(5,"sell_refund"); insert.executeUpdate();
        }
        try (PreparedStatement update=c.prepareStatement("UPDATE sell_pending SET status='DELIVERED' WHERE idem_key=? AND status='REFUND_OWED'")) { update.setString(1,r.getString(1)); update.executeUpdate(); }
        count++;
      }}
    }
    try (PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM pending_deliveries WHERE player_uuid=? AND source='sell_refund' AND status='offen'")) {
      q.setString(1,player); try(ResultSet r=q.executeQuery()) { r.next(); return r.getInt(1); }
    }
  }
  enum CommitStatus { COMMITTED, NOT_COMMITTED, UNKNOWN }
  interface Connections { Connection open() throws SQLException; }

  static <T> T run(Connection c, String key, CraftCore.Work<T> work) throws Exception {
    try {
      c.setAutoCommit(false);
      T result = work.run(c);
      if (key != null) try (PreparedStatement s = c.prepareStatement("INSERT INTO sell_txn (idem_key, status) VALUES (?, 'COMMITTED')")) {
        s.setString(1, key);
        s.executeUpdate();
      }
      c.commit();
      return result;
    } catch (Exception e) {
      try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
      throw e;
    }
  }

  // JDBC exposes no portable, reliable indication that a manual transaction has ended.
  // Wait for the helper-owned connection to close before treating absence as definitive.
  static CommitStatus checkCommitStatus(String key, Connection original, Duration timeout, Connections connections) {
    if (key == null || original == null || timeout == null || timeout.isNegative()) return CommitStatus.UNKNOWN;
    long start = System.nanoTime();
    long limit;
    try { limit = timeout.toNanos(); } catch (ArithmeticException e) { limit = Long.MAX_VALUE; }
    try {
      while (!original.isClosed()) {
        long remaining = limit - (System.nanoTime() - start);
        if (remaining <= 0) return CommitStatus.UNKNOWN;
        long sleep = Math.min(remaining, 10_000_000L);
        Thread.sleep(sleep / 1_000_000L, (int)(sleep % 1_000_000L));
      }
      try (Connection fresh = connections.open(); PreparedStatement s = fresh.prepareStatement("SELECT 1 FROM sell_txn WHERE idem_key = ?")) {
        s.setString(1, key);
        try (ResultSet r = s.executeQuery()) { return r.next() ? CommitStatus.COMMITTED : CommitStatus.NOT_COMMITTED; }
      }
    } catch (SQLException e) { return CommitStatus.UNKNOWN; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); return CommitStatus.UNKNOWN; }
  }
}
