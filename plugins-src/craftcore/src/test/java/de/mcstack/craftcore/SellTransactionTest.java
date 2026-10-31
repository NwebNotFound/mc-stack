package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellTransactionTest {
  private static final Duration WAIT = Duration.ofMillis(100);
  private SellTransaction.Connections h2() {
    String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    return () -> DriverManager.getConnection(url);
  }
  private void schema(SellTransaction.Connections db, boolean maria) throws Exception {
    try (Connection c = db.open(); Statement s = c.createStatement()) {
      s.execute(maria ? SellTransaction.DDL : SellTransaction.DDL.replace(" ENGINE=InnoDB", ""));
      s.execute("CREATE TABLE IF NOT EXISTS sell_txn_test_data (id VARCHAR(64) PRIMARY KEY)");
    }
  }
  private void commit(SellTransaction.Connections db, String key) throws Exception {
    try (Connection c = db.open()) {
      SellTransaction.run(c, key, tx -> { try (PreparedStatement s = tx.prepareStatement("INSERT INTO sell_txn_test_data VALUES (?)")) { s.setString(1, key); s.executeUpdate(); } return null; });
    }
  }
  @Test void committedOnFreshConnection() throws Exception {
    var db = h2(); schema(db, false); Connection original = db.open();
    SellTransaction.run(original, "yes", c -> null); original.close();
    assertEquals(SellTransaction.CommitStatus.COMMITTED, SellTransaction.checkCommitStatus("yes", original, WAIT, db));
  }
  @Test void absentAfterCloseIsNotCommitted() throws Exception {
    var db = h2(); schema(db, false); Connection original = db.open(); original.close();
    assertEquals(SellTransaction.CommitStatus.NOT_COMMITTED, SellTransaction.checkCommitStatus("no", original, WAIT, db));
  }
  @Test void openOrLookupFailureIsUnknown() throws Exception {
    var db = h2(); schema(db, false);
    try (Connection original = db.open()) {
      assertEquals(SellTransaction.CommitStatus.UNKNOWN, SellTransaction.checkCommitStatus("no", original, Duration.ZERO, () -> { fail("must wait for close"); return null; }));
      original.close();
      assertEquals(SellTransaction.CommitStatus.UNKNOWN, SellTransaction.checkCommitStatus("no", original, WAIT, () -> { throw new SQLException(); }));
    }
  }
  @Test void rollbackRemovesBothWorkAndKey() throws Exception {
    var db = h2(); schema(db, false); rollbackCheck(db, "rollback");
  }
  private void rollbackCheck(SellTransaction.Connections db, String key) throws Exception {
    try (Connection real = db.open()) {
      Connection failing = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
        if (m.getName().equals("commit")) throw new SQLException("simulated before commit");
        try { return m.invoke(real, a); } catch (InvocationTargetException e) { throw e.getCause(); }
      });
      assertThrows(SQLException.class, () -> SellTransaction.run(failing, key, c -> {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO sell_txn_test_data VALUES (?)")) { s.setString(1, key); s.executeUpdate(); }
        return null;
      }));
    }
    try (Connection c = db.open()) {
      for (String table : new String[]{"sell_txn", "sell_txn_test_data"}) {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + (table.equals("sell_txn") ? "idem_key" : "id") + " = ?")) {
          s.setString(1, key); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); assertEquals(0, r.getInt(1)); }
        }
      }
    }
  }
  @Test void mariaDb() throws Exception {
    String password = System.getenv("CRAFTCORE_DB_PASSWORD");
    assumeTrue(password != null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
    SellTransaction.Connections db = () -> DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore", "craftcore", password);
    schema(db, true);
    String key = UUID.randomUUID().toString(); commit(db, key);
    Connection original = db.open(); original.close();
    assertEquals(SellTransaction.CommitStatus.COMMITTED, SellTransaction.checkCommitStatus(key, original, WAIT, db));
    rollbackCheck(db, UUID.randomUUID().toString());
  }
}
