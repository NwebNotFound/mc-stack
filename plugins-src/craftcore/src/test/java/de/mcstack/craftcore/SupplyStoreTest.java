package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SupplyStoreTest {
  static final long H = 3_600_000L;

  static String url() { return "jdbc:h2:mem:supply_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"; }

  static Connection open(String url) throws Exception {
    Connection c = DriverManager.getConnection(url);
    SupplyStore.migrate(c);
    return c;
  }

  @Test void concurrentFlushesDoNotLoseDemand() throws Exception {
    String url = url();
    try (Connection setup = open(url)) {}
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Callable<Void>> jobs = new ArrayList<>();
    for (int t = 0; t < 4; t++) jobs.add(() -> {
      try (Connection c = DriverManager.getConnection(url)) {
        c.setAutoCommit(false);
        for (int i = 0; i < 50; i++) {
          for (int attempt = 0; ; attempt++) {
            try { SupplyStore.addDemand(c, Map.of("STONE", 3L), Map.of("STONE", 72d), 1_000L); c.commit(); break; }
            catch (java.sql.SQLException e) { c.rollback(); if (attempt > 50) throw e; }
          }
        }
      }
      return null;
    });
    for (Future<Void> f : pool.invokeAll(jobs)) f.get();
    pool.shutdown();
    try (Connection c = DriverManager.getConnection(url)) {
      assertEquals(4 * 50 * 3, SupplyStore.load(c).get("STONE").demand(), 1e-9, "every flush is counted once");
    }
  }

  @Test void demandDecaysInsideTheRowUpdate() throws Exception {
    try (Connection c = open(url())) {
      SupplyStore.addDemand(c, Map.of("STONE", 100L, "STONE_SLAB", -200L), Map.of("STONE", 12d, "STONE_SLAB", 12d), 0);
      SupplyStore.addDemand(c, Map.of("STONE", 10L), Map.of("STONE", 12d), 12 * H);
      var rows = SupplyStore.load(c);
      assertEquals(60, rows.get("STONE").demand(), 1e-9, "100 halved after 12 h, plus 10");
      assertEquals(12 * H, rows.get("STONE").demandUpdated());
      assertEquals(-200, rows.get("STONE_SLAB").demand(), 1e-9);
      assertTrue(rows.get("STONE").smoothed() < 0, "a demand-only row is not zero stock");
      SupplyStore.addDemand(c, Map.of("STONE", 0L), Map.of(), 24 * H);
      assertEquals(60, SupplyStore.load(c).get("STONE").demand(), 1e-9, "a zero delta writes nothing");
    }
  }

  @Test void emptyServerKeepsSupply() throws Exception {
    try (Connection c = open(url())) {
      for (int p = 0; p < 3; p++) SupplyStore.replaceHolder(c, "player:" + p, Map.of("DIAMOND", 10L), 0);
      assertEquals(3, SupplyStore.recompute(c, 0.25, 0, 30 * 24 * H, 3));
      double before = SupplyStore.load(c).get("DIAMOND").smoothed();
      assertEquals(30, before, 1e-9);
      // Everyone logs out: their last inventories stay, so ten more passes leave the EMA where it was.
      for (int i = 1; i <= 10; i++) SupplyStore.recompute(c, 0.25, i * H, 30 * 24 * H, 3);
      assertEquals(before, SupplyStore.load(c).get("DIAMOND").smoothed(), 1e-9, "price is not pinned to 'no stock'");
    }
  }

  @Test void olderSnapshotNeverOverwritesANewerOne() throws Exception {
    try (Connection c = open(url())) {
      assertTrue(SupplyStore.replaceHolder(c, "player:a", Map.of("DIAMOND", 0L), 2_000), "quit snapshot, empty inventory");
      assertFalse(SupplyStore.replaceHolder(c, "player:a", Map.of("DIAMOND", 64L), 1_000), "delayed join snapshot is older");
      try (var r = c.createStatement().executeQuery("SELECT SUM(qty) FROM market_supply_holder WHERE holder='player:a' AND item='DIAMOND'")) {
        r.next(); assertEquals(0, r.getLong(1));
      }
      assertTrue(SupplyStore.replaceHolder(c, "player:a", Map.of("DIAMOND", 5L), 3_000));
    }
  }

  @Test void tooFewHoldersDoNotMoveTheEma() throws Exception {
    try (Connection c = open(url())) {
      for (int p = 0; p < 3; p++) SupplyStore.replaceHolder(c, "player:" + p, Map.of("DIAMOND", 10L), 0);
      SupplyStore.recompute(c, 0.25, 0, 30 * 24 * H, 3);
      // 31 days later only one fresh player is left after the TTL purge.
      SupplyStore.replaceHolder(c, "player:new", Map.of(), 31 * 24 * H);
      assertEquals(0, SupplyStore.recompute(c, 0.25, 31 * 24 * H, 30 * 24 * H, 3));
      assertEquals(30, SupplyStore.load(c).get("DIAMOND").smoothed(), 1e-9);
    }
  }

  @Test void freshHoldersSlideTheEma() throws Exception {
    try (Connection c = open(url())) {
      for (int p = 0; p < 4; p++) SupplyStore.replaceHolder(c, "player:" + p, p == 0 ? Map.of("DIAMOND", 40L) : Map.of(), 0);
      SupplyStore.recompute(c, 0.25, 0, 30 * 24 * H, 3);
      SupplyStore.replaceHolder(c, "player:0", Map.of(), H);
      SupplyStore.recompute(c, 0.25, H, 30 * 24 * H, 3);
      assertEquals(30, SupplyStore.load(c).get("DIAMOND").smoothed(), 1e-9, "one step of alpha towards 0");
    }
  }
}
