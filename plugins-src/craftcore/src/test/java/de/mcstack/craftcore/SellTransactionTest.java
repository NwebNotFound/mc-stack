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
      s.execute(maria ? SellTransaction.PENDING_DDL : SellTransaction.PENDING_DDL.replace(" ENGINE=InnoDB", ""));
      s.execute(SellTransaction.PENDING_MIGRATION);
      s.execute(SellTransaction.STATUS_MIGRATION);
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
  @Test void stackCreditAndJournalCommitTogether() throws Exception {
    var db=h2(); schema(db,false);
    try(Connection c=db.open(); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE accounts_test (id VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL)");
      s.execute("CREATE TABLE journal_test (id VARCHAR(64) PRIMARY KEY, amount INT NOT NULL, cp BIGINT NOT NULL)");
      s.execute("INSERT INTO accounts_test VALUES ('player', 100)");
      SellTransaction.run(c,"stack",tx->{
        try(Statement q=tx.createStatement()) {
          q.executeUpdate("UPDATE accounts_test SET balance=balance+250 WHERE id='player'");
          q.executeUpdate("INSERT INTO journal_test VALUES ('stack', 5, 250)");
        }
        return null;
      });
      try(ResultSet r=s.executeQuery("SELECT balance FROM accounts_test WHERE id='player'")){assertTrue(r.next()); assertEquals(350,r.getLong(1));}
      try(ResultSet r=s.executeQuery("SELECT amount,cp FROM journal_test WHERE id='stack'")){assertTrue(r.next()); assertEquals(5,r.getInt(1)); assertEquals(250,r.getLong(2));}
      try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sell_txn WHERE idem_key='stack'")){assertTrue(r.next()); assertEquals(1,r.getInt(1));}
    }
  }
  @Test void pendingSchemaStoresRecoveryDetails() throws Exception {
    var db=h2(); schema(db,false);
    try(Connection c=db.open(); PreparedStatement q=c.prepareStatement("INSERT INTO sell_pending(idem_key,player_uuid,item_nbt,amount,cp) VALUES(?,?,?,?,?)")) {
      q.setString(1,"key"); q.setString(2,UUID.randomUUID().toString()); q.setString(3,"serialized-item"); q.setInt(4,4); q.setLong(5,120); q.executeUpdate();
      try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT item_nbt,amount,cp,created_at FROM sell_pending WHERE idem_key='key'")) {assertTrue(r.next());assertEquals("serialized-item",r.getString(1));assertEquals(4,r.getInt(2));assertEquals(120,r.getLong(3));assertNotNull(r.getTimestamp(4));}
    }
  }
  @Test void offlineRefundIsQueuedOnceForTheRightPlayer() throws Exception {
    var db=h2(); schema(db,false);
    try(Connection c=db.open(); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE pending_deliveries (id VARCHAR(64) PRIMARY KEY, player_uuid VARCHAR(36), item_nbt LONGTEXT, amount INT, source VARCHAR(32), status VARCHAR(16))");
      SellTransaction.refundOwed(c,"refund","player","serialized",4);
      assertEquals(0,SellTransaction.queueRefunds(c,"other"));
      c.setAutoCommit(false);
      assertEquals(1,SellTransaction.queueRefunds(c,"player")); c.commit();
      assertEquals(1,SellTransaction.queueRefunds(c,"player")); c.commit();
      try(ResultSet r=s.executeQuery("SELECT p.status,d.item_nbt,d.amount,d.status FROM sell_pending p JOIN pending_deliveries d ON d.id=p.idem_key")) {
        assertTrue(r.next()); assertEquals("DELIVERED",r.getString(1)); assertEquals("serialized",r.getString(2)); assertEquals(4,r.getInt(3)); assertEquals("offen",r.getString(4)); assertFalse(r.next());
      }
    }
  }
  @Test void sellFeatureDefaultsOffAndCanBeEnabled() {
    var cfg=new org.bukkit.configuration.MemoryConfiguration();
    assertFalse(Market.marketSellEnabled(cfg));
    cfg.set("market.sell.enabled",true); assertTrue(Market.marketSellEnabled(cfg));
    cfg.set("market.sell.enabled",false); assertFalse(Market.marketSellEnabled(cfg));
  }
  @Test void mariaDb() throws Exception {
    String password = System.getenv("CRAFTCORE_DB_PASSWORD");
    assumeTrue(password != null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
    SellTransaction.Connections db = () -> DriverManager.getConnection(System.getenv().getOrDefault("CRAFTCORE_TEST_DB_URL", "jdbc:mariadb://craftcore-db:3306/craftcore"), System.getenv().getOrDefault("CRAFTCORE_TEST_DB_USER", "craftcore"), password);
    schema(db, true);
    String key = UUID.randomUUID().toString(); commit(db, key);
    Connection original = db.open(); original.close();
    assertEquals(SellTransaction.CommitStatus.COMMITTED, SellTransaction.checkCommitStatus(key, original, WAIT, db));
    rollbackCheck(db, UUID.randomUUID().toString());
    String player=UUID.randomUUID().toString(), sale=UUID.randomUUID().toString();
    try(Connection c=db.open(); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid CHAR(36) PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0)");
      s.execute("CREATE TABLE IF NOT EXISTS bank_journal (id CHAR(36) PRIMARY KEY, player CHAR(36) NOT NULL, item VARCHAR(64) NOT NULL, amount INT NOT NULL, cp BIGINT NOT NULL, status VARCHAR(24) NOT NULL)");
      SellTransaction.run(c,sale,tx->{
        try(PreparedStatement q=tx.prepareStatement("INSERT INTO bank_journal(id,player,item,amount,cp,status) VALUES(?,?,?, ?,?, 'PENDING')")) {
          q.setString(1,sale);q.setString(2,player);q.setString(3,"STONE");q.setInt(4,16);q.setLong(5,160);q.executeUpdate();
        }
        assertTrue(BankJournal.finishSale(tx,sale,item->{})); return null;
      });
      try(PreparedStatement q=c.prepareStatement("SELECT a.balance,j.amount,j.cp,j.status,t.status FROM accounts a JOIN bank_journal j ON j.player=a.uuid JOIN sell_txn t ON t.idem_key=j.id WHERE j.id=?")) {
        q.setString(1,sale);try(ResultSet r=q.executeQuery()){assertTrue(r.next());assertEquals(160,r.getLong(1));assertEquals(16,r.getInt(2));assertEquals(160,r.getLong(3));assertEquals("DONE",r.getString(4));assertEquals("COMMITTED",r.getString(5));}
      }
    }
  }
}
