package de.mcstack.craftcore;
import java.sql.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SafetyTest {
 @Test void deliveryClaimOnlyOnce() throws Exception {try(Connection c=DriverManager.getConnection("jdbc:h2:mem:claim;MODE=MySQL")){c.createStatement().execute("CREATE TABLE pending_deliveries(id VARCHAR(36),player_uuid VARCHAR(36),amount INT,status VARCHAR(16))");c.createStatement().execute("INSERT INTO pending_deliveries VALUES('x','p',1,'offen')");assertTrue(Deliveries.claim(c,"x","p",1));assertFalse(Deliveries.claim(c,"x","p",1));}}
 @Test void concurrentWithdrawalsCannotOverdraw()throws Exception {String url="jdbc:h2:mem:withdraw;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";try(Connection c=DriverManager.getConnection(url)){c.createStatement().execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY,balance BIGINT)");c.createStatement().execute("INSERT INTO accounts VALUES('p',100)");ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> run=()->{try(Connection x=DriverManager.getConnection(url)){x.setAutoCommit(false);boolean ok=EconomyStore.change(x,"p",-80);x.commit();return ok;}};Future<Boolean>a=pool.submit(run),b=pool.submit(run);assertNotEquals(a.get(),b.get());try(ResultSet r=c.createStatement().executeQuery("SELECT balance FROM accounts WHERE uuid='p'")){assertTrue(r.next());assertEquals(20,r.getLong(1));}}finally{pool.shutdownNow();}}}
}
