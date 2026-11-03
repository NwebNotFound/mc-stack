package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The bank buys every survival-obtainable item. Dirt, sand, gravel, cobblestone, stone, netherrack
 * and tools were blocked by a stale {@code market.unsellable} list, so the whole material list is
 * checked here, not a hand-picked sample.
 */
class SellableMaterialTest {
  private static final List<Material> NOT_OBTAINABLE = List.of(
    Material.BARRIER, Material.BEDROCK, Material.LIGHT, Material.COMMAND_BLOCK,
    Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.COMMAND_BLOCK_MINECART,
    Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.JIGSAW, Material.DEBUG_STICK,
    Material.KNOWLEDGE_BOOK, Material.SPAWNER, Material.TRIAL_SPAWNER, Material.ZOMBIE_SPAWN_EGG,
    Material.PLAYER_HEAD, Material.END_PORTAL_FRAME, Material.FARMLAND, Material.PETRIFIED_OAK_SLAB);

  private static YamlConfiguration shippedConfig() { return ArbitrageTest.load("config.yml"); }

  @Test void shippedConfigBlocksNothing() {
    assertTrue(shippedConfig().getStringList("market.unsellable").isEmpty(),
      "unsellable ist absichtlich leer: Erde, Sand, Kies und Werkzeug sind verkaufbar");
  }

  @Test void everyObtainableMaterialIsSellable() {
    YamlConfiguration cfg = shippedConfig();
    List<String> blocked = new ArrayList<>();
    for (Material material : Material.values()) {
      // Material#isItem needs the live item registry, so the name rule decides here: it already
      // drops air, technical and legacy materials.
      if (!SurvivalItems.obtainable(cfg, material)) continue;
      if (!Market.sellable(cfg, material)) blocked.add(material.name());
    }
    assertTrue(blocked.isEmpty(), "Bank kauft diese erhaeltlichen Items nicht: " + blocked);
  }

  @Test void dirtSandGravelCobblestoneAndToolsAreSellable() {
    YamlConfiguration cfg = shippedConfig();
    for (Material material : List.of(Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
      Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE,
      Material.STONE, Material.NETHERRACK, Material.KELP, Material.IRON_SWORD, Material.IRON_PICKAXE,
      Material.DIAMOND_SHOVEL)) {
      assertTrue(Market.sellable(cfg, material), material.name());
    }
  }

  @Test void nonObtainableItemsStayUnsellable() {
    YamlConfiguration cfg = shippedConfig();
    for (Material material : NOT_OBTAINABLE) {
      assertFalse(SurvivalItems.obtainable(cfg, material), material.name());
      assertFalse(Market.sellable(cfg, material), material.name());
    }
  }

  @Test void adminEntryInUnsellableStillBlocksOnlyThatItem() {
    YamlConfiguration cfg = shippedConfig();
    cfg.set("market.unsellable", List.of("SAND"));
    assertFalse(Market.sellable(cfg, Material.SAND));
    assertTrue(Market.sellable(cfg, Material.DIRT));
    assertTrue(Market.sellable(cfg, Material.GRAVEL));
  }
}
