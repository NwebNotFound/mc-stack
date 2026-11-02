package de.mcstack.craftcore;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketSnapshotTest {
  @Test void snapshotsAreIdempotentAndPreserveTradeAggregates() throws Exception {
    try(Connection c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL"); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE market_catalog(item VARCHAR(64) PRIMARY KEY,buy_price DECIMAL(20,2),sell_price DECIMAL(20,2),base_price DECIMAL(20,4),updated_at BIGINT)");
      s.execute("CREATE TABLE market_price_history(item VARCHAR(64),hour_ts BIGINT,avg_price DECIMAL(20,4),min_price DECIMAL(20,4),max_price DECIMAL(20,4),volume_qty BIGINT,quote_price DECIMAL(20,4),PRIMARY KEY(item,hour_ts))");
      s.execute("INSERT INTO market_catalog VALUES('STONE',2,1,1,0),('DIRT',4,3,1,0)");
      s.execute("INSERT INTO market_price_history VALUES('DIRT',7200,2,1,3,10,NULL)");
      WebsiteStats.snapshot(c,Instant.ofEpochSecond(7300));
      s.execute("UPDATE market_catalog SET sell_price=1.5 WHERE item='STONE'");
      WebsiteStats.snapshot(c,Instant.ofEpochSecond(7400));
      try(ResultSet r=s.executeQuery("SELECT item,avg_price,min_price,max_price,volume_qty,quote_price FROM market_price_history ORDER BY item")) {
        assertTrue(r.next()); assertEquals("DIRT",r.getString(1)); assertEquals(2,r.getDouble(2)); assertEquals(1,r.getDouble(3)); assertEquals(3,r.getDouble(4)); assertEquals(10,r.getLong(5)); assertEquals(3,r.getDouble(6));
        assertTrue(r.next()); assertEquals("STONE",r.getString(1)); assertEquals(1,r.getDouble(2)); assertEquals(1,r.getDouble(3)); assertEquals(1,r.getDouble(4)); assertEquals(0,r.getLong(5)); assertEquals(1,r.getDouble(6)); assertFalse(r.next());
      }
    }
  }
}
