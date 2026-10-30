package de.mcstack.craftcore;

import java.sql.*;

/**
 * Dynamic bank price per item (switch market.dynamic.enabled, default false).
 * State: log pressure P (factor = exp(P), clamped to [floor, ceiling]). P decays toward 0 (factor 1.0)
 * by wall-clock time with a half-life, so it keeps recovering while the server is offline.
 * Each trade adds the marginal log-dampened change in the net volume over the rolling window:
 * dP = -s*(g(V+q)-g(V)), with g(v)=sign(v)*ln(1+|v|/scale), V = net volume (sells positive) in the window before the trade.
 * Repeated dumping within the window therefore accumulates, but with diminishing effect per item.
 * Buy and buyback use the same factor, so the spread stays the same.
 */
final class DynamicPrice {
  record Settings(boolean enabled, double halfLifeHours, double windowHours, double floor, double ceiling, double scale, double strength) {
    Settings {
      if(!(halfLifeHours>0) || !(windowHours>0) || !(floor>0) || !(ceiling>=floor) || !(scale>0) || !(strength>=0)) throw new IllegalArgumentException("market.dynamic ungueltig");
    }
    long windowMillis() { return (long)(windowHours*3600000.0); }
  }
  static final Settings DEFAULTS = new Settings(false, 72, 72, 0.3, 3.0, 64, 0.25);
  static Settings settings(org.bukkit.configuration.Configuration cfg) {
    String p="market.dynamic.";
    return new Settings(cfg.getBoolean(p+"enabled",false), cfg.getDouble(p+"half-life-hours",DEFAULTS.halfLifeHours()), cfg.getDouble(p+"window-hours",DEFAULTS.windowHours()),
      cfg.getDouble(p+"floor",DEFAULTS.floor()), cfg.getDouble(p+"ceiling",DEFAULTS.ceiling()), cfg.getDouble(p+"volume-scale",DEFAULTS.scale()), cfg.getDouble(p+"strength",DEFAULTS.strength()));
  }
  private DynamicPrice() {}

  static double decay(double pressure, long elapsedMillis, Settings s) {
    return pressure*Math.pow(0.5, Math.max(0,elapsedMillis)/(s.halfLifeHours()*3600000.0));
  }
  static double dampened(double volume, Settings s) { return Math.signum(volume)*Math.log1p(Math.abs(volume)/s.scale()); }
  /** sold>0: player sells to the bank (price down); sold<0: player buys from the bank (price up). */
  static double impact(long windowNetSold, long sold, Settings s) {
    return -s.strength()*(dampened(windowNetSold+sold,s)-dampened(windowNetSold,s));
  }
  static double factor(double pressure, Settings s) { return Math.max(s.floor(), Math.min(s.ceiling(), Math.exp(pressure))); }
  /** Pressure is limited to the band, so a factor parked at the floor/ceiling recovers right away. */
  static double clampPressure(double pressure, Settings s) { return Math.max(Math.log(s.floor()), Math.min(Math.log(s.ceiling()), pressure)); }
  /** Same factor for buy and buyback: the ratio buy/sell (spread) does not change. */
  static long apply(long cents, double factor, boolean buy) { double v=cents*factor; return buy ? Math.max(1,(long)Math.ceil(v-1e-9)) : Math.max(0,(long)Math.floor(v+1e-9)); }

  static void migrate(Connection c) throws SQLException {
    try(Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS bank_dynamic_state (item VARCHAR(64) NOT NULL PRIMARY KEY, pressure DOUBLE NOT NULL, updated BIGINT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS bank_dynamic_volume (item VARCHAR(64) NOT NULL, at BIGINT NOT NULL, sold INT NOT NULL)");
      try { s.execute("CREATE INDEX bank_dynamic_volume_item ON bank_dynamic_volume(item, at)"); } catch(SQLException exists) {}
    }
  }
  /** Current factor (no write). */
  static double current(Connection c, String item, long now, Settings s) throws SQLException {
    if(!s.enabled()) return 1.0;
    try(PreparedStatement q=c.prepareStatement("SELECT pressure,updated FROM bank_dynamic_state WHERE item=?")) {
      q.setString(1,item); try(ResultSet r=q.executeQuery()) { return r.next() ? factor(decay(r.getDouble(1),now-r.getLong(2),s),s) : 1.0; }
    }
  }
  /** Record a trade (caller owns the transaction). Returns the new factor. */
  static double trade(Connection c, String item, long sold, long now, Settings s) throws SQLException {
    if(!s.enabled() || sold==0) return current(c,item,now,s);
    double pressure=0; long updated=now;
    try(PreparedStatement q=c.prepareStatement("SELECT pressure,updated FROM bank_dynamic_state WHERE item=? FOR UPDATE")) {
      q.setString(1,item); try(ResultSet r=q.executeQuery()) { if(r.next()) { pressure=r.getDouble(1); updated=r.getLong(2); } }
    }
    long window=0;
    try(PreparedStatement q=c.prepareStatement("SELECT COALESCE(SUM(sold),0) FROM bank_dynamic_volume WHERE item=? AND at>?")) {
      q.setString(1,item); q.setLong(2,now-s.windowMillis()); try(ResultSet r=q.executeQuery()) { r.next(); window=r.getLong(1); }
    }
    pressure=clampPressure(decay(pressure,now-updated,s)+impact(window,sold,s),s);
    try(PreparedStatement q=c.prepareStatement("DELETE FROM bank_dynamic_volume WHERE item=? AND at<=?")) { q.setString(1,item); q.setLong(2,now-s.windowMillis()); q.executeUpdate(); }
    try(PreparedStatement q=c.prepareStatement("INSERT INTO bank_dynamic_volume(item,at,sold) VALUES(?,?,?)")) { q.setString(1,item); q.setLong(2,now); q.setLong(3,sold); q.executeUpdate(); }
    try(PreparedStatement q=c.prepareStatement("UPDATE bank_dynamic_state SET pressure=?,updated=? WHERE item=?")) {
      q.setDouble(1,pressure); q.setLong(2,now); q.setString(3,item);
      if(q.executeUpdate()==0) try(PreparedStatement i=c.prepareStatement("INSERT INTO bank_dynamic_state(item,pressure,updated) VALUES(?,?,?)")) { i.setString(1,item); i.setDouble(2,pressure); i.setLong(3,now); i.executeUpdate(); }
    }
    return factor(pressure,s);
  }
}
