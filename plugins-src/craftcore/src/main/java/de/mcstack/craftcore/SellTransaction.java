package de.mcstack.craftcore;

import java.sql.*;
import java.time.Duration;

final class SellTransaction {
  static final String DDL = "CREATE TABLE IF NOT EXISTS sell_txn (idem_key VARCHAR(64) PRIMARY KEY, status VARCHAR(16) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB";
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
