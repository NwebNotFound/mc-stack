package de.mcstack.craftcore;

import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SellModesTest {
  @Test void parsesHandItemAllAndGui() {
    assertEquals(SellModes.Kind.GUI, SellModes.parse(new String[0]).kind());
    assertEquals(SellModes.Kind.GUI, SellModes.parse(new String[] {"gui"}).kind());
    assertEquals(SellModes.Kind.HAND, SellModes.parse(new String[] {"hand"}).kind());
    assertEquals(10, SellModes.parse(new String[] {"hand", "10"}).amount());
    assertNotNull(SellModes.parse(new String[] {"hand", "0"}).error());
    assertEquals(SellModes.Kind.ALL, SellModes.parse(new String[] {"all"}).kind());
    assertEquals(SellModes.Kind.ALL_CONFIRM, SellModes.parse(new String[] {"all", "bestaetigen"}).kind());
    assertEquals(SellModes.Kind.ALL_CONFIRM, SellModes.parse(new String[] {"alles", "confirm"}).kind());
    SellModes.Parsed stone = SellModes.parse(new String[] {"cobblestone", "32"});
    assertEquals(SellModes.Kind.ITEM, stone.kind());
    assertEquals(Material.COBBLESTONE, stone.material());
    assertEquals(32, stone.amount());
    assertNotNull(SellModes.parse(new String[] {"not_an_item"}).error());
    assertNotNull(SellModes.parse(new String[] {"hand", "1", "extra"}).error());
  }

  @Test void tabCompleteOffersModesThenAmounts() {
    assertEquals(List.of("hand", "all", "gui"), SellModes.complete(new String[] {""}));
    assertTrue(SellModes.complete(new String[] {"co"}).contains("cobblestone"));
    assertFalse(SellModes.complete(new String[] {""}).contains("cobblestone"));
    assertEquals(List.of("bestaetigen", "confirm"), SellModes.complete(new String[] {"all", ""}));
    assertEquals(List.of("1", "64"), SellModes.complete(new String[] {"hand", ""}));
    assertEquals(List.of("1", "64"), SellModes.complete(new String[] {"dirt", ""}));
  }

  @Test void selectionRespectsAmountAndPerTradeCap() {
    var slots = List.of(
      new SellModes.Slot(0, "DIRT", 64),
      new SellModes.Slot(1, "IRON_SWORD", 1),
      new SellModes.Slot(2, "DIRT", 64));
    assertEquals(List.of(10), SellModes.take(slots, "DIRT", 10, CurvePrice.MAX_QTY).stream().map(SellModes.Piece::amount).toList());
    assertEquals(List.of(64, 64), SellModes.take(slots, "DIRT", null, CurvePrice.MAX_QTY).stream().map(SellModes.Piece::amount).toList());
    assertEquals(List.of(1), SellModes.take(slots, "IRON_SWORD", null, CurvePrice.MAX_QTY).stream().map(SellModes.Piece::index).toList());
    var chunks = SellModes.take(List.of(new SellModes.Slot(3, "COBBLESTONE", 5000)), "COBBLESTONE", null, CurvePrice.MAX_QTY);
    assertEquals(List.of(2304, 2304, 392), chunks.stream().map(SellModes.Piece::amount).toList());
    assertTrue(chunks.stream().allMatch(piece -> piece.amount() <= CurvePrice.MAX_QTY));
    assertEquals(2, Market.affordable(250, 100, 5));
  }

  @Test void closingTheChestReturnsDepositsNotButtons() {
    var left = SellModes.leftovers(List.of(
      new SellModes.Slot(0, "DIRT", 16),
      new SellModes.Slot(44, "SAND", 1),
      new SellModes.Slot(SellModes.PREVIEW_SLOT, "PAPER", 1),
      new SellModes.Slot(SellModes.CONFIRM_SLOT, "LIME_WOOL", 1),
      new SellModes.Slot(SellModes.CLOSE_SLOT, "BARRIER", 1)));
    assertEquals(List.of(0, 44), left.stream().map(SellModes.Slot::index).toList());
  }

  @Test void survivalItemsSellAndUnobtainableOnesDoNot() throws Exception {
    YamlConfiguration cfg = RecipeArbitrageTest.liveCfg();
    Set<String> blocked = Set.copyOf(cfg.getStringList("market.unsellable"));
    assertTrue(Market.marketSellEnabled(cfg));
    for (String name : List.of("DIRT", "COBBLESTONE", "SAND", "IRON_SWORD", "IRON_PICKAXE", "DIAMOND_CHESTPLATE")) {
      Material material = Material.matchMaterial(name);
      assertNotNull(material, name);
      assertTrue(SurvivalItems.obtainable(cfg, material), name);
      assertFalse(blocked.contains(name), name);
    }
    for (String name : List.of("BARRIER", "BEDROCK", "COMMAND_BLOCK", "SPAWNER", "FARMLAND", "PLAYER_HEAD")) {
      assertFalse(SurvivalItems.obtainable(cfg, Material.matchMaterial(name)), name);
    }
    var pricing = new SellPricing.Config(100, java.util.Map.of(), 500, 0, null);
    assertEquals(500, SellPricing.calculate(1000, List.of(), 125, 250, pricing).total());
    SellPricing.Breakdown enchanted = SellPricing.calculate(1000, List.of(new SellPricing.Enchant("sharpness", 1, false)), 0, 250, pricing);
    assertTrue(enchanted.total() > 1000 && enchanted.total() <= 1500, "enchanted " + enchanted.total());
    assertTrue(cfg.getStringList("market.unsellable").isEmpty());
    assertTrue(Market.gearResidueBlocks(true, false, false, false, false, false));
    assertTrue(Market.gearResidueBlocks(false, true, false, false, false, false));
    assertTrue(Market.gearResidueBlocks(false, false, true, false, false, false));
    assertTrue(Market.gearResidueBlocks(false, false, false, true, false, false));
    assertTrue(Market.gearResidueBlocks(false, false, false, false, true, false));
    assertTrue(Market.gearResidueBlocks(false, false, false, false, false, true));
    assertFalse(Market.gearResidueBlocks(false, false, false, false, false, false));
    assertTrue(SellModes.pullsButtons(InventoryAction.COLLECT_TO_CURSOR, 0, 54));
    assertTrue(SellModes.pullsButtons(InventoryAction.PICKUP_ALL, SellModes.CONFIRM_SLOT, 54));
    assertTrue(SellModes.pullsButtons(InventoryAction.MOVE_TO_OTHER_INVENTORY, SellModes.CLOSE_SLOT, 54));
    assertFalse(SellModes.pullsButtons(InventoryAction.PICKUP_ALL, 0, 54));
    assertFalse(SellModes.sameOffer(true, true, false, 64, 1));
    assertFalse(SellModes.sameOffer(true, false, true, 64, 1));
    assertFalse(SellModes.sameOffer(true, true, true, 1, 2));
    assertTrue(SellModes.sameOffer(true, true, true, 64, 32));
    assertFalse(SellModes.sellAllOpen(null, 1_000));
    assertTrue(SellModes.sellAllOpen(1_000L, 1_000L + SellModes.SELL_ALL_TTL_MS));
    assertFalse(SellModes.sellAllOpen(1_000L, 1_000L + SellModes.SELL_ALL_TTL_MS + 1));
    long sell = CurvePrice.rawSell(12.5, new CurvePrice.Factors(1.5, 0.75, 0.5, 72, 12));
    long buy = CurvePrice.buy0(12.5, new CurvePrice.Factors(1.5, 0.75, 0.5, 72, 12));
    assertTrue(sell * 2L <= buy, sell + " vs " + buy);
  }
}
