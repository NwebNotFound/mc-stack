package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class BankJournalTest {
  private Connection setup(String name) throws Exception {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL;DB_CLOSE_DELAY=-1");
    try(Statement s=c.createStatement()) {s.execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT)");s.execute("CREATE TABLE bank_journal(id VARCHAR(36) PRIMARY KEY,player VARCHAR(36),item VARCHAR(64),cp BIGINT,status VARCHAR(24))");s.execute("INSERT INTO bank_journal VALUES('j','p','STONE',125,'PENDING')");}
    c.setAutoCommit(false); return c;
  }
  private long value(Connection c,String query) throws Exception {try(ResultSet r=c.createStatement().executeQuery(query)){assertTrue(r.next());return r.getLong(1);}}
  @Test void pendingSaleIsRecoveredAndRepeatedExecutionDoesNotCreditTwice() throws Exception {
    try(Connection c=setup("recover")) {int[] moves={0}; assertTrue(BankJournal.finishSale(c,"j",m->moves[0]++));c.commit();assertFalse(BankJournal.finishSale(c,"j",m->moves[0]++));c.commit();assertEquals(125,value(c,"SELECT balance FROM accounts WHERE uuid='p'"));assertEquals(1,moves[0]);}
  }
  @Test void failureAfterCreditRollsBackAndCanBeRetried() throws Exception {
    try(Connection c=setup("rollback")) {assertThrows(SQLException.class,()->BankJournal.finishSale(c,"j",m->{throw new SQLException("crash");}));c.rollback();assertEquals(0,value(c,"SELECT COUNT(*) FROM accounts"));assertTrue(BankJournal.finishSale(c,"j",m->{}));c.commit();assertEquals(125,value(c,"SELECT balance FROM accounts"));}
  }
}
