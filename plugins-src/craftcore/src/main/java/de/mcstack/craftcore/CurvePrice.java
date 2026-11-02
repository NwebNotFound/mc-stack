package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.bukkit.configuration.Configuration;

/** Per-item exponential bank curve. Reads do not insert rows; trades use INSERT IGNORE then FOR UPDATE. */
final class CurvePrice {
  static final int MAX_QTY = 2304;
  static final double CHEAP_BASE = 1.0;
  record Factors(double buyFactor, double sellFactor, double sellRatio, double halfLifeHours, double halfLifeCheapHours) {}
  record Row(double net, long updated) {}
  record Cent(long buy, long sell) {}

  private CurvePrice() {}

  static Factors factors(Configuration cfg) {
    double buy = cfg == null ? 1.5 : cfg.getDouble("market.buy-factor", 1.5);
    double sell = cfg == null ? 0.75 : cfg.getDouble("market.sell-factor", 0.75);
    double ratio = cfg == null ? 0.5 : cfg.getDouble("market.max-sell-ratio", 0.5);
    double hours = cfg == null ? 72 : cfg.getDouble("market.curve.half-life-hours", 72);
    double cheap = cfg == null ? 12 : cfg.getDouble("market.curve.half-life-cheap-hours", 12);
    if (!Double.isFinite(buy) || buy <= 0) buy = 1.5;
    if (!Double.isFinite(sell) || sell < 0) sell = 0.75;
    if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) ratio = 0.5;
    if (!Double.isFinite(hours) || hours <= 0) hours = 72;
    if (!Double.isFinite(cheap) || cheap <= 0) cheap = 12;
    return new Factors(buy, sell, ratio, hours, cheap);
  }

  static double depth(double base) {
    if (base < CHEAP_BASE) return 128;
    if (base < 50) return 250;
    return 400;
  }

  static double halfLifeHours(double base, Factors factors) {
    return base < CHEAP_BASE ? factors.halfLifeCheapHours() : factors.halfLifeHours();
  }

  static double lo(double base) { return 0.25 * base; }
  static double hi(double base) { return 1.2 * base; }

  static double mid(double base, double net) {
    double value = base * Math.exp(net / depth(base));
    return Math.min(hi(base), Math.max(lo(base), value));
  }

  /** Elapsed &lt;= 0 leaves net unchanged, including a clock skew. */
  static double fold(double net, double base, long elapsedMs, Factors factors) {
    if (elapsedMs <= 0 || net == 0) return net;
    double window = halfLifeHours(base, factors) * 3_600_000d;
    return net * Math.pow(0.5, elapsedMs / window);
  }

  static long buy0(double mid, Factors factors) {
    return Math.max(1, PriceModel.factorQuote(mid, factors.buyFactor()));
  }

  static long rawSell(double mid, Factors factors) {
    long buy = buy0(mid, factors);
    long sell = PriceModel.factorQuote(mid, factors.sellFactor());
    sell = Math.min(sell, PriceModel.cappedSell(sell, buy, factors.sellRatio()));
    return Math.max(1, sell);
  }

  static long buyCents(double mid, Factors factors) {
    long buy = buy0(mid, factors);
    long sell = rawSell(mid, factors);
    return Math.max(buy, sell + 1);
  }

  static long sellCents(double mid, Long cap, Factors factors) {
    long sell = rawSell(mid, factors);
    if (cap != null) sell = Math.min(sell, Math.max(1, cap));
    return sell;
  }

  static Cent cents(double base, double net, Long sellCap, Factors factors) {
    double m = mid(base, net);
    return new Cent(buyCents(m, factors), sellCents(m, sellCap, factors));
  }

  /** Sum of the pre-step sell cents for qty units, starting at net and stepping down. */
  static long sumSell(double base, double net, int qty, Long sellCap, Factors factors) {
    if (qty < 1 || qty > MAX_QTY) throw new IllegalArgumentException("qty");
    long total = 0;
    for (int i = 0; i < qty; i++) total += sellCents(mid(base, net - i), sellCap, factors);
    return total;
  }

  static long sumBuy(double base, double net, int qty, Factors factors) {
    if (qty < 1 || qty > MAX_QTY) throw new IllegalArgumentException("qty");
    long total = 0;
    for (int i = 0; i < qty; i++) total += buyCents(mid(base, net + i), factors);
    return total;
  }

  static void migrate(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS market_curve (item VARCHAR(64) PRIMARY KEY, net DOUBLE NOT NULL, updated BIGINT NOT NULL)");
    }
  }

  /** Insert a missing row without colliding, then lock it. */
  static Row lock(Connection c, String item, long now) throws SQLException {
    try (PreparedStatement insert = c.prepareStatement("INSERT IGNORE INTO market_curve(item, net, updated) VALUES(?, 0, ?)")) {
      insert.setString(1, item);
      insert.setLong(2, now);
      insert.executeUpdate();
    }
    try (PreparedStatement query = c.prepareStatement("SELECT net, updated FROM market_curve WHERE item=? FOR UPDATE")) {
      query.setString(1, item);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) throw new SQLException("market_curve missing: " + item);
        return new Row(row.getDouble(1), row.getLong(2));
      }
    }
  }

  static Row read(Connection c, String item) throws SQLException {
    try (PreparedStatement query = c.prepareStatement("SELECT net, updated FROM market_curve WHERE item=?")) {
      query.setString(1, item);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return new Row(0, 0);
        return new Row(row.getDouble(1), row.getLong(2));
      }
    }
  }

  static double folded(Row row, double base, long now, Factors factors) {
    if (row.updated() <= 0) return row.net();
    return fold(row.net(), base, Math.max(0, now - row.updated()), factors);
  }

  static void save(Connection c, String item, double net, long updated) throws SQLException {
    try (PreparedStatement update = c.prepareStatement("UPDATE market_curve SET net=?, updated=? WHERE item=?")) {
      update.setDouble(1, net);
      update.setLong(2, updated);
      update.setString(3, item);
      if (update.executeUpdate() != 1) throw new SQLException("market_curve update: " + item);
    }
  }
}
