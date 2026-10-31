package de.mcstack.craftcore;

import java.sql.*;

/** Transactional per-item price pressure, decaying over wall-clock time. */
final class DynamicPrice {
  static final double FLOOR=0.8, CEILING=1.2, STEP=0.003;
  static final long HALF_LIFE_MS=72L*3600000;
  private DynamicPrice() {}
  static double decay(double pressure,long elapsed) {
    return pressure*Math.pow(0.5,Math.max(0,elapsed)/(double)HALF_LIFE_MS);
  }
  static double factor(double pressure) { return Math.max(FLOOR,Math.min(CEILING,1+pressure)); }
  static double move(double pressure,long sold,int stackSize) {
    return factor(pressure-STEP*sold/stackSize)-1;
  }
  static long apply(long cents,double factor,boolean buy) {
    double value=cents*factor;
    return buy ? Math.max(1,(long)Math.ceil(value-1e-9)) : Math.max(0,(long)Math.floor(value+1e-9));
  }
  static void migrate(Connection c) throws SQLException {
    try(Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS bank_dynamic_state (item VARCHAR(64) NOT NULL PRIMARY KEY, pressure DOUBLE NOT NULL, updated BIGINT NOT NULL)");
    }
  }
  /** Create and lock before quoting, so concurrent trades cannot use stale quotes. */
  static double current(Connection c,String item,long now) throws SQLException {
    try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO bank_dynamic_state(item,pressure,updated) VALUES(?,0,?)")) {
      q.setString(1,item); q.setLong(2,now); q.executeUpdate();
    }
    try(PreparedStatement q=c.prepareStatement("SELECT pressure,updated FROM bank_dynamic_state WHERE item=? FOR UPDATE")) {
      q.setString(1,item); try(ResultSet r=q.executeQuery()) {
        if(!r.next()) throw new SQLException("Dynamic price state missing: "+item);
        return factor(decay(Math.max(FLOOR-1,Math.min(CEILING-1,r.getDouble(1))),now-r.getLong(2)));
      }
    }
  }
  /** sold > 0 means a sale to the bank; caller owns the transaction and lock. */
  static double trade(Connection c,String item,long sold,int stackSize,long now) throws SQLException {
    if(stackSize<1) throw new IllegalArgumentException("stackSize");
    double pressure=move(current(c,item,now)-1,sold,stackSize);
    try(PreparedStatement q=c.prepareStatement("UPDATE bank_dynamic_state SET pressure=?,updated=? WHERE item=?")) {
      q.setDouble(1,pressure); q.setLong(2,now); q.setString(3,item); q.executeUpdate();
    }
    return factor(pressure);
  }
}
