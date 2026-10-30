package de.mcstack.craftcore;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Web-shop fulfillment. ready: item is in /abholen. collected: the player took it.
 * failed and refunded are stored only through {@link #mark}. Shop buy is not modified;
 * an in-flight rcon row (empty response) is linked by a database trigger, and
 * {@link #backfill} pairs already committed OK purchases with deliveries.
 */
final class OrderFulfillment {
  static final String VIEW = "CREATE OR REPLACE VIEW v_order_fulfillment AS SELECT request_id, status, updated_at FROM order_fulfillment WHERE status IN ('ready','collected','failed','refunded')";
  static final String TRIGGER_SQL = "CREATE TRIGGER trg_order_fulfillment_ready AFTER INSERT ON pending_deliveries FOR EACH ROW INSERT IGNORE INTO order_fulfillment (request_id, delivery_id, status, updated_at) SELECT CONVERT(r.idem_key USING utf8mb4), NEW.id, 'ready', CURRENT_TIMESTAMP FROM rcon_purchases r WHERE r.response='' AND r.buyer=NEW.player_uuid AND r.qty=NEW.amount AND r.kind=CASE NEW.source WHEN 'shop_journal' THEN 'shop' WHEN 'ah_journal' THEN 'ah' ELSE '' END AND NOT EXISTS (SELECT 1 FROM order_fulfillment f WHERE f.request_id=CONVERT(r.idem_key USING utf8mb4)) ORDER BY r.created_at DESC LIMIT 1";
  private static final Set<String> STATUSES = Set.of("ready","collected","failed","refunded");
  private OrderFulfillment() {}
  record Purchase(String requestId, String kind, String buyer, int qty, String response) {}

  static void migrate(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS order_fulfillment (request_id VARCHAR(64) NOT NULL PRIMARY KEY, delivery_id CHAR(36) NULL, status VARCHAR(16) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX order_fulfillment_delivery (delivery_id), CONSTRAINT order_fulfillment_status CHECK (status IN ('ready','collected','failed','refunded')))");
      s.execute(VIEW);
      ReadGrants.apply(s, "v_order_fulfillment");
    }
    installTrigger(c);
  }
  static void installTrigger(Connection c) throws SQLException {
    String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (!product.contains("maria") && !product.contains("mysql")) return;
    try (Statement s = c.createStatement()) {
      s.execute("DROP TRIGGER IF EXISTS trg_order_fulfillment_ready");
      try { s.execute(TRIGGER_SQL); }
      catch (SQLException e) {
        try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_order_fulfillment_ready'")) {
          if (r.next() && r.getInt(1) > 0) return;
        }
        throw e;
      }
    }
  }
  static void mark(Connection c, String requestId, String status) throws SQLException {
    if (!STATUSES.contains(status)) throw new IllegalArgumentException(status);
    try (PreparedStatement q = c.prepareStatement("INSERT INTO order_fulfillment(request_id,delivery_id,status,updated_at) VALUES(?,NULL,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE status=VALUES(status), updated_at=CURRENT_TIMESTAMP")) {
      q.setString(1, requestId); q.setString(2, status); q.executeUpdate();
    }
  }
  static void collected(Connection c, String deliveryId) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("UPDATE order_fulfillment SET status='collected', updated_at=CURRENT_TIMESTAMP WHERE delivery_id=? AND status='ready'")) {
      q.setString(1, deliveryId); q.executeUpdate();
    }
  }
  static void retarget(Connection c, String deliveryId, String nextId) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("UPDATE order_fulfillment SET delivery_id=?, updated_at=CURRENT_TIMESTAMP WHERE delivery_id=? AND status='ready'")) {
      q.setString(1, nextId); q.setString(2, deliveryId); q.executeUpdate();
    }
  }
  static int backfill(Connection c) throws SQLException {
    List<Purchase> purchases = new ArrayList<>();
    try (PreparedStatement q = c.prepareStatement("SELECT idem_key, kind, buyer, qty, response FROM rcon_purchases WHERE response='' OR response LIKE 'OK%' ORDER BY created_at, idem_key")) {
      try (ResultSet r = q.executeQuery()) {
        while (r.next()) {
          byte[] key = r.getBytes(1);
          if (key == null) continue;
          String requestId = new String(key, StandardCharsets.US_ASCII);
          if (!requestId.matches("[A-Za-z0-9_-]{1,64}")) continue;
          purchases.add(new Purchase(requestId, r.getString(2), r.getString(3), r.getInt(4), r.getString(5) == null ? "" : r.getString(5)));
        }
      }
    }
    int linked = 0;
    for (Purchase purchase : purchases) {
      if (exists(c, purchase.requestId())) continue;
      String source = sourceFor(purchase.kind());
      if (source == null) continue;
      String delivery = findDelivery(c, purchase.buyer(), purchase.qty(), source, "offen");
      String status = "ready";
      if (delivery == null && purchase.response().startsWith("OK")) {
        delivery = findDelivery(c, purchase.buyer(), purchase.qty(), source, "geliefert");
        status = "collected";
      }
      if (delivery == null) continue;
      insert(c, purchase.requestId(), delivery, status);
      linked++;
    }
    return linked;
  }
  private static String sourceFor(String kind) {
    if ("shop".equals(kind)) return "shop_journal";
    if ("ah".equals(kind)) return "ah_journal";
    return null;
  }
  private static boolean exists(Connection c, String requestId) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("SELECT request_id FROM order_fulfillment WHERE request_id=?")) {
      q.setString(1, requestId);
      try (ResultSet r = q.executeQuery()) { return r.next(); }
    }
  }
  private static String findDelivery(Connection c, String buyer, int qty, String source, String status) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("SELECT id FROM pending_deliveries WHERE player_uuid=? AND amount=? AND source=? AND status=? AND id NOT IN (SELECT delivery_id FROM order_fulfillment WHERE delivery_id IS NOT NULL) ORDER BY created_at, id LIMIT 1")) {
      q.setString(1, buyer); q.setInt(2, qty); q.setString(3, source); q.setString(4, status);
      try (ResultSet r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
    }
  }
  private static void insert(Connection c, String requestId, String deliveryId, String status) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("INSERT INTO order_fulfillment(request_id,delivery_id,status,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)")) {
      q.setString(1, requestId); q.setString(2, deliveryId); q.setString(3, status); q.executeUpdate();
    }
  }
}
