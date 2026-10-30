package de.mcstack.craftcore;

import java.sql.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicPriceTest {
  static final DynamicPrice.Settings S = new DynamicPrice.Settings(true, 72, 72, 0.3, 3.0, 64, 0.25);
  static final ZoneId Z = ZoneId.of("Europe/Berlin");
  static long at(String local) { return LocalDateTime.parse(local).atZone(Z).toInstant().toEpochMilli(); }
  private Connection db(String name) throws SQLException {
    Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL"); DynamicPrice.migrate(c); return c;
  }
  @Test void defaultIsDisabledAndNeutral() throws Exception {
    assertFalse(DynamicPrice.DEFAULTS.enabled());
    try(Connection c=db("dyn_off")) {
      var off=new DynamicPrice.Settings(false,72,72,0.3,3.0,64,0.25);
      assertEquals(1.0, DynamicPrice.trade(c,"DIRT",5000,at("2026-10-30T18:00"),off));
      assertEquals(1.0, DynamicPrice.current(c,"DIRT",at("2026-10-30T18:00"),off));
    }
  }
  @Test void dirtDumpFridayHalfRecoveredSundayNotReset() throws Exception {
    try(Connection c=db("dyn_dump")) {
      long friday=at("2026-10-30T00:00"), sunday=at("2026-11-01T23:59");
      double dumped=DynamicPrice.trade(c,"DIRT",2304,friday,S);
      assertTrue(dumped<0.5, "dump lowers price: "+dumped);
      double sun=DynamicPrice.current(c,"DIRT",sunday,S);
      double recovered=(Math.log(sun)-Math.log(dumped))/(0-Math.log(dumped));
      assertEquals(0.5, recovered, 0.02, "about half recovered in log space after one half-life");
      assertTrue(sun<0.8, "not reset: "+sun);
      assertTrue(sun>dumped);
    }
  }
  @Test void decayUsesWallClockEvenWithoutTrades() throws Exception {
    try(Connection c=db("dyn_wall")) {
      long t=at("2026-10-30T12:00");
      double f0=DynamicPrice.trade(c,"DIRT",1000,t,S);
      double later=DynamicPrice.current(c,"DIRT",t+30L*24*3600000,S);
      assertTrue(later>0.99 && later<=1.0, "fully recovers after offline month: "+later);
      assertTrue(f0<later);
    }
  }
  @Test void repeatedDumpingInWindowAccumulatesWithDampening() throws Exception {
    try(Connection c=db("dyn_acc")) {
      long t=at("2026-10-30T12:00");
      double a=DynamicPrice.trade(c,"DIRT",640,t,S);
      double b=DynamicPrice.trade(c,"DIRT",640,t+3600000,S);
      double d=DynamicPrice.trade(c,"DIRT",640,t+2*3600000,S);
      assertTrue(b<a && d<b, "accumulates: "+a+" "+b+" "+d);
      assertTrue((Math.log(a)-Math.log(b)) < (0-Math.log(a)), "second dump has less effect (log-dampened)");
      // outside the window the dampening starts over
      double fresh=DynamicPrice.impact(0,640,S), inWindow=DynamicPrice.impact(1280,640,S);
      assertTrue(Math.abs(inWindow)<Math.abs(fresh));
    }
  }
  @Test void clampedToBand() throws Exception {
    try(Connection c=db("dyn_clamp")) {
      long t=at("2026-10-30T12:00");
      for(int i=0;i<50;i++) DynamicPrice.trade(c,"DIRT",100000,t+i,S);
      assertEquals(0.3, DynamicPrice.current(c,"DIRT",t+50,S), 1e-6);
      for(int i=0;i<100;i++) DynamicPrice.trade(c,"DIAMOND",-100000,t+i,S);
      assertEquals(3.0, DynamicPrice.current(c,"DIAMOND",t+100,S), 1e-6);
    }
  }
  @Test void buyAndBuybackShareFactorSoSpreadIsKept() {
    long buy=900, sell=450;
    for(double f: new double[]{0.3,0.5,0.77,1.0,1.9,3.0}) {
      long b=DynamicPrice.apply(buy,f,true), s=DynamicPrice.apply(sell,f,false);
      assertEquals((double)buy/sell, (double)b/s, 0.01, "spread at f="+f);
    }
  }
}
