package de.mcstack.craftcore;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicPriceTest {
  private Connection db(String name) throws SQLException {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL");
    DynamicPrice.migrate(c); DynamicPrice.migrate(c); c.setAutoCommit(false); return c;
  }
  @Test void perStackBoundsAndRecovery() throws Exception {
    try(Connection c=db("dynamic")) {
      long now=1_000_000;
      assertEquals(0.997,DynamicPrice.trade(c,"STONE",64,64,now),1e-9);
      assertEquals(1,DynamicPrice.trade(c,"STONE",-64,64,now),1e-9);
      assertEquals(.8,DynamicPrice.trade(c,"STONE",64000,64,now),1e-9);
      assertEquals(.9,DynamicPrice.current(c,"STONE",now+DynamicPrice.HALF_LIFE_MS),1e-9);
      assertEquals(1.2,DynamicPrice.trade(c,"STONE",-64000,64,now),1e-9);
      c.rollback(); assertEquals(1,DynamicPrice.current(c,"STONE",now),1e-9);
    }
  }
  @Test void dumpThenBuyBackCannotProfitIncludingRoundingAndClamps() throws Exception {
    try(Connection c=db("arbitrage_dynamic")) {
      long now=1000000;
      for(long base:new long[]{1,2,5,100,10000}) for(int qty:new int[]{1,64,512,2304}) {
        String item="I"+base+"_"+qty; long proceeds=0,cost=0;
        for(int i=0;i<qty;i++) {
          double f=DynamicPrice.current(c,item,now);
          proceeds+=Math.min(DynamicPrice.apply(base,f,false),DynamicPrice.apply(base,DynamicPrice.FLOOR,true));
          DynamicPrice.trade(c,item,1,64,now);
        }
        for(int i=0;i<qty;i++) {
          double f=DynamicPrice.current(c,item,now);
          cost+=DynamicPrice.apply(base,f,true);
          DynamicPrice.trade(c,item,-1,64,now);
        }
        assertTrue(proceeds<=cost,base+" "+qty+": "+proceeds+" > "+cost);
      }
    }
  }
  @Test void websiteCatalogAndEventAreUpdatedAndRolledBackTogether() throws Exception {
    try(Connection c=db("website_dynamic")) {
      try(Statement s=c.createStatement()) {
        s.execute("CREATE TABLE market_catalog(item VARCHAR(64) PRIMARY KEY,buy_price DECIMAL(20,2),sell_price DECIMAL(20,2),base_price DECIMAL(20,4),updated_at BIGINT)");
        s.execute("CREATE TABLE market_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,item VARCHAR(64),qty INT,unit_price DECIMAL(20,4),total_cp DECIMAL(20,2),source VARCHAR(8),ts BIGINT)");
      }
      WebsiteStats.catalog(c,"STONE",150,25,1,1000); c.commit();
      double f=DynamicPrice.trade(c,"STONE",64,64,2000);
      WebsiteStats.catalog(c,"STONE",DynamicPrice.apply(150,f,true),DynamicPrice.apply(25,f,false),1,2000);
      WebsiteStats.event(c,"STONE",64,1600,"bank");
      try(ResultSet r=c.createStatement().executeQuery("SELECT sell_price,updated_at FROM market_catalog WHERE item='STONE'")) {
        assertTrue(r.next()); assertEquals(0.24,r.getDouble(1),1e-9); assertEquals(2,r.getLong(2));
      }
      try(ResultSet r=c.createStatement().executeQuery("SELECT qty,total_cp FROM market_events")) { assertTrue(r.next()); assertEquals(64,r.getInt(1)); assertEquals(16,r.getDouble(2)); }
      c.rollback();
      try(ResultSet r=c.createStatement().executeQuery("SELECT sell_price FROM market_catalog")) { assertTrue(r.next()); assertEquals(.25,r.getDouble(1),1e-9); }
      try(ResultSet r=c.createStatement().executeQuery("SELECT COUNT(*) FROM market_events")) { assertTrue(r.next()); assertEquals(0,r.getInt(1)); }
    }
  }

}
