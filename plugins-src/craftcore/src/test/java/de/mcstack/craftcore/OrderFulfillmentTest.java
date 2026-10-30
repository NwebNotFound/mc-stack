package de.mcstack.craftcore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderFulfillmentTest {
  private Connection db() throws SQLException {
    Connection c = schema();
    OrderFulfillment.migrate(c);
    return c;
  }
  private static void purchase(Connection c, String id, String kind, String buyer, int qty, String response, long at) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("INSERT INTO rcon_purchases VALUES(?,?,?,?,?,?)")) {
      q.setBytes(1, id.getBytes(StandardCharsets.US_ASCII)); q.setString(2, kind); q.setString(3, buyer); q.setInt(4, qty); q.setString(5, response); q.setLong(6, at); q.executeUpdate();
    }
  }
  private static void delivery(Connection c, String id, String player, int amount, String source, String status, String at) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("INSERT INTO pending_deliveries VALUES(?,?,?,?,?,?)")) {
      q.setString(1, id); q.setString(2, player); q.setInt(3, amount); q.setString(4, source); q.setString(5, status); q.setTimestamp(6, Timestamp.valueOf(at)); q.executeUpdate();
    }
  }
  @Test void openDeliveryIsReadyAndPickupCollectsIt() throws Exception {
    try (Connection c = db()) {
      purchase(c, "web-1", "shop", "buyer", 4, "OK 4 100 0", recent(1_000));
      delivery(c, "d1", "buyer", 4, "shop_journal", "offen", "2026-10-30 10:00:00");
      assertEquals(1, OrderFulfillment.backfill(c));
      assertEquals(0, OrderFulfillment.backfill(c));
      assertRow(c, "web-1", "ready");
      OrderFulfillment.collected(c, "d1");
      assertRow(c, "web-1", "collected");
      OrderFulfillment.collected(c, "d1");
      assertRow(c, "web-1", "collected");
    }
  }
  @Test void partialPickupStaysReadyUntilTheRemainderIsTaken() throws Exception {
    try (Connection c = db()) {
      try (PreparedStatement q = c.prepareStatement("INSERT INTO order_fulfillment(request_id,delivery_id,status,updated_at) VALUES('web-2','d1','ready',CURRENT_TIMESTAMP)")) { q.executeUpdate(); }
      OrderFulfillment.retarget(c, "d1", "d2");
      try (ResultSet r = c.createStatement().executeQuery("SELECT delivery_id, status FROM order_fulfillment WHERE request_id='web-2'")) {
        assertTrue(r.next()); assertEquals("d2", r.getString(1)); assertEquals("ready", r.getString(2));
      }
      assertRow(c, "web-2", "ready");
      OrderFulfillment.collected(c, "d1");
      assertRow(c, "web-2", "ready");
      OrderFulfillment.collected(c, "d2");
      assertRow(c, "web-2", "collected");
    }
  }
  @Test void pairsPurchasesInTimeOrderAndIgnoresErrors() throws Exception {
    try (Connection c = db()) {
      long now = System.currentTimeMillis();
      purchase(c, "web-a", "shop", "buyer", 2, "OK 2 1 0", now - 3_000);
      purchase(c, "web-b", "shop", "buyer", 2, "OK 2 1 0", now - 2_000);
      purchase(c, "web-err", "shop", "buyer", 2, "ERR NO_FUNDS", now - 1_000);
      delivery(c, "early", "buyer", 2, "shop_journal", "offen", "2026-10-30 09:00:00");
      delivery(c, "late", "buyer", 2, "shop_journal", "offen", "2026-10-30 09:05:00");
      assertEquals(2, OrderFulfillment.backfill(c));
      try (ResultSet r = c.createStatement().executeQuery("SELECT request_id, delivery_id FROM order_fulfillment ORDER BY request_id")) {
        assertTrue(r.next()); assertEquals("web-a", r.getString(1)); assertEquals("early", r.getString(2));
        assertTrue(r.next()); assertEquals("web-b", r.getString(1)); assertEquals("late", r.getString(2));
        assertFalse(r.next());
      }
      purchase(c, "web-done", "ah", "buyer", 3, "OK 3 9 0", now - 500);
      delivery(c, "taken", "buyer", 3, "ah_journal", "geliefert", "2026-10-30 11:00:00");
      assertEquals(1, OrderFulfillment.backfill(c));
      assertRow(c, "web-done", "collected");
    }
  }
  @Test void inFlightPurchaseLinksWhileTheResponseIsStillEmpty() throws Exception {
    try (Connection c = db()) {
      purchase(c, "web-live", "shop", "buyer", 1, "", recent(1_000));
      delivery(c, "open", "buyer", 1, "shop_journal", "offen", "2026-10-30 12:00:00");
      assertEquals(1, OrderFulfillment.backfill(c));
      assertRow(c, "web-live", "ready");
    }
  }
  @Test void failedAndRefundedStayInTheViewAndUnknownStatusDoesNot() throws Exception {
    try (Connection c = db()) {
      OrderFulfillment.mark(c, "web-f", "failed");
      OrderFulfillment.mark(c, "web-r", "refunded");
      assertRow(c, "web-f", "failed");
      assertRow(c, "web-r", "refunded");
      OrderFulfillment.mark(c, "web-f", "refunded");
      assertRow(c, "web-f", "refunded");
      assertThrows(IllegalArgumentException.class, () -> OrderFulfillment.mark(c, "web-x", "offen"));
      assertTrue(OrderFulfillment.TRIGGER_SQL.startsWith("CREATE OR REPLACE TRIGGER"));
      assertFalse(OrderFulfillment.TRIGGER_SQL.contains("DROP TRIGGER"));
      assertTrue(OrderFulfillment.TRIGGER_SQL.contains("response=''"));
      assertTrue(OrderFulfillment.TRIGGER_SQL.contains("'ready'"));
      assertTrue(OrderFulfillment.TRIGGER_SQL.contains("INSERT IGNORE"));
      String sql = OrderFulfillment.backfillSql("MariaDB");
      assertTrue(sql.contains("LEFT JOIN order_fulfillment"));
      assertTrue(sql.contains("f.request_id IS NULL"));
      assertTrue(sql.contains("r.created_at>=?"));
    }
  }
  @Test void backfillIgnoresRowsOlderThanSevenDays() throws Exception {
    try (Connection c = db()) {
      purchase(c, "web-old", "shop", "buyer", 1, "OK 1 1 0", System.currentTimeMillis() - OrderFulfillment.BACKFILL_WINDOW_MILLIS - 60_000);
      delivery(c, "old", "buyer", 1, "shop_journal", "offen", "2026-10-01 10:00:00");
      assertEquals(0, OrderFulfillment.backfill(c));
      purchase(c, "web-new", "shop", "buyer", 1, "OK 1 1 0", recent(1_000));
      delivery(c, "fresh", "buyer", 1, "shop_journal", "offen", "2026-10-30 10:00:00");
      assertEquals(1, OrderFulfillment.backfill(c));
      assertEquals(0, OrderFulfillment.backfill(c));
      assertRow(c, "web-new", "ready");
    }
  }
  @Test void createTriggerFailureStillStartsAndBackfills() throws Exception {
    try (Connection raw = schema()) {
      purchase(raw, "web-trig", "shop", "buyer", 2, "OK 2 1 0", recent(1_000));
      delivery(raw, "box", "buyer", 2, "shop_journal", "offen", "2026-10-30 10:00:00");
      List<String> warnings = new ArrayList<>();
      OrderFulfillment.startup(denyTrigger(raw), warnings::add);
      assertFalse(warnings.isEmpty());
      assertTrue(warnings.get(0).contains("Backfill"));
      assertTrue(warnings.get(0).contains("TRIGGER command denied"));
      assertRow(raw, "web-trig", "ready");
    }
  }
  private static Connection schema() throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:h2:mem:ful_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE pending_deliveries(id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36), amount INT, source VARCHAR(32), status VARCHAR(16), created_at TIMESTAMP)");
      s.execute("CREATE TABLE rcon_purchases(idem_key VARBINARY(64) PRIMARY KEY, kind VARCHAR(8), buyer VARCHAR(36), qty INT, response VARCHAR(80), created_at BIGINT)");
    }
    return c;
  }
  private static Connection denyTrigger(Connection raw) {
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
      if ("createStatement".equals(method.getName()) || "prepareStatement".equals(method.getName())) {
        Object stmt = method.invoke(raw, args);
        Class<?> type = "prepareStatement".equals(method.getName()) ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (sp, sm, sargs) -> {
          if (sargs != null && sargs.length > 0 && sargs[0] instanceof String sql && sql.toUpperCase(Locale.ROOT).contains("TRIGGER") && sm.getName().startsWith("execute"))
            throw new SQLException("TRIGGER command denied");
          return invoke(sm, stmt, sargs);
        });
      }
      return invoke(method, raw, args);
    });
  }
  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException e) { if (e.getCause() != null) throw e.getCause(); throw e; }
  }
  private static long recent(long ageMillis) { return System.currentTimeMillis() - ageMillis; }
  private static void assertRow(Connection c, String requestId, String status) throws SQLException {
    try (PreparedStatement q = c.prepareStatement("SELECT request_id, status, updated_at FROM v_order_fulfillment WHERE request_id=?")) {
      q.setString(1, requestId);
      try (ResultSet r = q.executeQuery()) {
        assertTrue(r.next(), requestId);
        assertEquals(requestId, r.getString("request_id"));
        assertEquals(status, r.getString("status"));
        assertNotNull(r.getTimestamp("updated_at"));
        assertFalse(r.next());
      }
    }
  }
}
