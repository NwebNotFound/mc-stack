package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CraftDemandTest {
  @Test void shiftClickCountsWhatTheInventoryCanTake() {
    assertEquals(1, CraftDemand.crafts(false, new int[] {64, 64}, 4, 64, 0, 0, 64));
    assertEquals(16, CraftDemand.crafts(true, new int[] {64, 0, 16}, 4, 64, 36, 0, 0), "smallest filled slot limits");
    assertEquals(3, CraftDemand.crafts(true, new int[] {64}, 4, 64, 0, 12, 0), "only 12 free result slots");
    assertEquals(0, CraftDemand.crafts(true, new int[] {0, 0}, 1, 64, 36, 0, 0));
  }

  @Test void noOpClicksAddNoDemand() {
    assertEquals(0, CraftDemand.crafts(false, new int[] {64, 64}, 4, 64, 36, 0, 0), "incompatible cursor or no-op action");
    assertEquals(0, CraftDemand.crafts(false, new int[] {64}, 4, 64, 36, 0, 3), "cursor has room for 3, result is 4");
    assertEquals(0, CraftDemand.crafts(true, new int[] {64}, 4, 64, 0, 0, 0), "shift-click into a full inventory");
    assertTrue(CraftDemand.craftDelta(new String[] {"OAK_LOG"}, 0, "OAK_PLANKS", 4).isEmpty());
  }

  @Test void craftingNetsConsumptionAgainstTheResult() {
    String[] nine = new String[9];
    java.util.Arrays.fill(nine, "IRON_INGOT");
    assertEquals(Map.of("IRON_INGOT", 9L, "IRON_BLOCK", -1L), CraftDemand.craftDelta(nine, 1, "IRON_BLOCK", 1));
    assertEquals(Map.of("IRON_BLOCK", 2L, "IRON_INGOT", -18L), CraftDemand.craftDelta(new String[] {"IRON_BLOCK", null, "AIR"}, 2, "IRON_INGOT", 9));
  }

  @Test void stonecutterOneInputPerTake() {
    assertEquals(64, CraftDemand.stonecutterTakes(true, 64, 2, 64, 36, 0, 0), "64 stone, two slabs each: 64 takes");
    assertEquals(1, CraftDemand.stonecutterTakes(false, 64, 2, 64, 0, 0, 64));
    assertEquals(0, CraftDemand.stonecutterTakes(false, 64, 2, 64, 0, 0, 1), "cursor cannot hold the result");
    assertEquals(Map.of("STONE", 64L, "STONE_SLAB", -128L), CraftDemand.stonecutterDelta("STONE", 64, "STONE_SLAB", 2));
  }

  @Test void smeltCountsSourceNotFuel() {
    assertEquals(Map.of("RAW_IRON", 1L, "IRON_INGOT", -1L), CraftDemand.smeltDelta("RAW_IRON", "IRON_INGOT", 1));
    assertTrue(CraftDemand.smeltDelta("AIR", "IRON_INGOT", 1).isEmpty());
  }

  @Test void reversibleCycleLeavesNoDemand() {
    DemandLedger ledger = new DemandLedger();
    String[] nine = new String[9];
    java.util.Arrays.fill(nine, "IRON_INGOT");
    for (int i = 0; i < 10_000; i++) {
      ledger.add(CraftDemand.craftDelta(nine, 1, "IRON_BLOCK", 1));
      ledger.add(CraftDemand.craftDelta(new String[] {"IRON_BLOCK"}, 1, "IRON_INGOT", 9));
    }
    assertTrue(ledger.drain().isEmpty(), "10000 ingot<->block cycles pump nothing");
  }

  @Test void ledgerBatchesThousandsOfSmeltsIntoOneRowPerItem() {
    DemandLedger ledger = new DemandLedger();
    for (int i = 0; i < 5000; i++) ledger.add(CraftDemand.smeltDelta("RAW_IRON", "IRON_INGOT", 1));
    Map<String, Long> batch = ledger.drain();
    assertEquals(Map.of("RAW_IRON", 5000L, "IRON_INGOT", -5000L), batch);
    assertTrue(ledger.drain().isEmpty());
  }

  @Test void ledgerLosesNothingWhileDraining() throws Exception {
    DemandLedger ledger = new DemandLedger();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    for (int t = 0; t < 4; t++) pool.submit(() -> { start.await(); for (int i = 0; i < 25_000; i++) ledger.add(Map.of("COBBLESTONE", 1L)); return null; });
    long seen = 0;
    start.countDown();
    pool.shutdown();
    while (!pool.awaitTermination(1, TimeUnit.MILLISECONDS)) seen += ledger.drain().getOrDefault("COBBLESTONE", 0L);
    seen += ledger.drain().getOrDefault("COBBLESTONE", 0L);
    assertEquals(100_000L, seen);
  }
}
