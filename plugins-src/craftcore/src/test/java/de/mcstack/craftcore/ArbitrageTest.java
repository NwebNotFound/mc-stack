package de.mcstack.craftcore;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArbitrageTest {
  @Test void knownPricesDoNotInheritFallbacks() {
    var cfg=new YamlConfiguration(); cfg.set("base.IRON_INGOT",8);
    var recipes=List.of(new PriceCatalog.CatalogRecipe(Material.IRON_BLOCK,1,Collections.nCopies(9,List.of(Material.IRON_INGOT))),
      new PriceCatalog.CatalogRecipe(Material.BREAD,1,Collections.nCopies(3,List.of(Material.WHEAT))));
    var known=PriceCatalog.known(cfg,recipes,m->true);
    assertTrue(known.contains(Material.IRON_BLOCK)); assertFalse(known.contains(Material.WHEAT)); assertFalse(known.contains(Material.BREAD));
  }
  @Test void prohibitedMaterialsAreNeverBankBuyable() {
    var cfg=load("config.yml");
    for(Material m:Material.values()) if(m.name().endsWith("_SPAWN_EGG") || m.name().endsWith("SHULKER_BOX") || m.name().startsWith("NETHERITE_")) assertTrue(Market.unbuyable(cfg,m),m.name());
    assertTrue(Market.unbuyable(cfg,Material.ENCHANTED_BOOK));
  }
  @Test void ironAndGoldBasePurchasesKeepNinefoldConversionSafe() {
    var cfg=load("config.yml"); var catalog=load("prices-base.yml");
    for(String metal:List.of("IRON","GOLD")) {
      Material ingot=Material.valueOf(metal+"_INGOT"), block=Material.valueOf(metal+"_BLOCK"), nugget=Material.valueOf(metal+"_NUGGET");
      long i=Market.cents(cfg,ingot,Market.quote(cfg,ingot,catalog.getDouble("base."+ingot)).base(),true);
      long b=Market.cents(cfg,block,Market.quote(cfg,block,catalog.getDouble("base."+block)).base(),true);
      long n=Market.cents(cfg,nugget,Market.quote(cfg,nugget,catalog.getDouble("base."+nugget)).base(),true);
      assertEquals(9*i,b); assertTrue(9*n>=i);
    }
  }
  @Test void registeredRecipeCostHandlesGoldChainAndWheatBread() {
    var recipes=List.of(
      new PriceCatalog.CatalogRecipe(Material.GOLD_INGOT,9,List.of(List.of(Material.GOLD_BLOCK))),
      new PriceCatalog.CatalogRecipe(Material.GOLD_NUGGET,9,List.of(List.of(Material.GOLD_INGOT))),
      new PriceCatalog.CatalogRecipe(Material.BREAD,1,Collections.nCopies(3,List.of(Material.WHEAT))));
    var costs=RecipeCost.derive(Map.of(Material.GOLD_BLOCK,1350L,Material.WHEAT,9L),recipes);
    assertEquals(1350.0/81,costs.get(Material.GOLD_NUGGET),1e-8);
    assertEquals(27.0,costs.get(Material.BREAD),1e-8);
  }
  record Recipe(Material input, int inputs, Material output, int outputs) {}
  static Recipe r(Material in, int n, Material out, int m) { return new Recipe(in,n,out,m); }
  static final List<Recipe> RECIPES=List.of(
    r(Material.IRON_BLOCK,1,Material.IRON_INGOT,9), r(Material.IRON_INGOT,9,Material.IRON_BLOCK,1),
    r(Material.IRON_INGOT,1,Material.IRON_NUGGET,9), r(Material.IRON_NUGGET,9,Material.IRON_INGOT,1),
    r(Material.RAW_IRON,1,Material.IRON_INGOT,1), r(Material.RAW_IRON_BLOCK,1,Material.RAW_IRON,9), r(Material.RAW_IRON,9,Material.RAW_IRON_BLOCK,1),
    r(Material.GOLD_BLOCK,1,Material.GOLD_INGOT,9), r(Material.GOLD_INGOT,9,Material.GOLD_BLOCK,1),
    r(Material.GOLD_INGOT,1,Material.GOLD_NUGGET,9), r(Material.GOLD_NUGGET,9,Material.GOLD_INGOT,1), r(Material.RAW_GOLD,1,Material.GOLD_INGOT,1),
    r(Material.EMERALD_BLOCK,1,Material.EMERALD,9), r(Material.EMERALD,9,Material.EMERALD_BLOCK,1),
    r(Material.REDSTONE_BLOCK,1,Material.REDSTONE,9), r(Material.REDSTONE,9,Material.REDSTONE_BLOCK,1),
    r(Material.LAPIS_BLOCK,1,Material.LAPIS_LAZULI,9), r(Material.LAPIS_LAZULI,9,Material.LAPIS_BLOCK,1),
    r(Material.COAL_BLOCK,1,Material.COAL,9), r(Material.COAL,9,Material.COAL_BLOCK,1),
    r(Material.COPPER_BLOCK,1,Material.COPPER_INGOT,9), r(Material.COPPER_INGOT,9,Material.COPPER_BLOCK,1),
    r(Material.DIAMOND_BLOCK,1,Material.DIAMOND,9), r(Material.DIAMOND,9,Material.DIAMOND_BLOCK,1),
    r(Material.OAK_LOG,1,Material.OAK_PLANKS,4), r(Material.OAK_PLANKS,2,Material.STICK,4),
    r(Material.COBBLESTONE,1,Material.STONE,1), r(Material.WHEAT,3,Material.BREAD,1),
    r(Material.WHEAT,9,Material.HAY_BLOCK,1), r(Material.HAY_BLOCK,1,Material.WHEAT,9),
    r(Material.BEEF,1,Material.COOKED_BEEF,1), r(Material.SAND,1,Material.GLASS,1));
  static YamlConfiguration load(String name) { return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(ArbitrageTest.class.getResourceAsStream("/"+name)),StandardCharsets.UTF_8)); }
  // A fractional per-item cost is a conservative lower bound: partial recipe
  // outputs are allowed in this calculation, so it cannot miss a cheaper chain
  // merely because recipe batches are indivisible.
  static Map<Material,Double> cheapest(Map<Material,Double> purchases, List<Recipe> recipes) {
    Map<Material,Double> costs=new EnumMap<>(Material.class); costs.putAll(purchases);
    for(int pass=0;pass<Material.values().length;pass++) {
      boolean changed=false;
      for(Recipe r:recipes) {
        Double input=costs.get(r.input()); if(input==null) continue;
        double cost=input*r.inputs()/r.outputs();
        if(cost+1e-10<costs.getOrDefault(r.output(),Double.POSITIVE_INFINITY)) { costs.put(r.output(),cost); changed=true; }
      }
      if(!changed) break;
    }
    return costs;
  }
  @Test void multiStepCostFindsCheaperRecipeChain() {
    var costs=cheapest(Map.of(Material.WHEAT,9.0,Material.HAY_BLOCK,100.0),List.of(
      r(Material.HAY_BLOCK,1,Material.WHEAT,9),r(Material.WHEAT,3,Material.BREAD,1),r(Material.WHEAT,9,Material.HAY_BLOCK,1)));
    assertEquals(27.0,costs.get(Material.BREAD),1e-9);
    var indirect=cheapest(Map.of(Material.HAY_BLOCK,45.0),List.of(r(Material.WHEAT,3,Material.BREAD,1),r(Material.HAY_BLOCK,1,Material.WHEAT,9)));
    assertEquals(15.0,indirect.get(Material.BREAD),1e-9);
  }
  @Test void wheatToBreadHasNoProfitAtIndependentPriceExtremes() {
    var cfg=load("config.yml"); var prices=load("prices-base.yml");
    var wheat=Market.quote(cfg,Material.WHEAT,prices.getDouble("base.WHEAT"));
    // Bread is recipe-derived: three wheat produce one loaf, with catalog markup.
    var bread=Market.quote(cfg,Material.BREAD,prices.getDouble("base.WHEAT")*3*1.1);
    assertTrue(3*Market.cents(cfg,Material.WHEAT,wheat.min(),true)>=Market.cents(cfg,Material.BREAD,bread.max(),false));
  }
  @Test void knownRecipesExposeUnsafeIndependentPriceBounds() {
    var cfg=load("config.yml"); var catalog=load("prices-base.yml");
    Set<Material> materials=EnumSet.noneOf(Material.class);
    RECIPES.forEach(r->{materials.add(r.input()); materials.add(r.output());});
    for(String key:catalog.getConfigurationSection("base").getKeys(false)) if(!materials.contains(Material.matchMaterial(key))) catalog.set("base."+key,null);
    var recipes=RECIPES.stream().map(r->new PriceCatalog.CatalogRecipe(r.output(),r.outputs(),Collections.nCopies(r.inputs(),List.of(r.input())))).toList();
    var prices=PriceCatalog.build(catalog,recipes,materials::contains,m->m==Material.BEEF?"nahrung":m==Material.RAW_IRON_BLOCK?"bloecke":"materialien");
    Map<Material,Double> purchases=new EnumMap<>(Material.class);
    for(Material m:materials) if(!Market.unbuyable(cfg,m)) { var q=Market.quote(cfg,m,prices.get(m)); purchases.put(m,(double)Market.cents(cfg,m,q.min(),true)); }
    var costs=cheapest(purchases,RECIPES); List<String> failures=new ArrayList<>();
    for(Material m:materials) if(costs.containsKey(m) && !cfg.getStringList("market.unsellable").contains(m.name())) {
      var q=Market.quote(cfg,m,prices.get(m)); long revenue=Market.cents(cfg,m,q.max(),false);
      if(costs.get(m)+1e-9<revenue) failures.add(m+" cost="+costs.get(m)+" revenue="+revenue);
    }
    // Mit den Preisgrenzen aus prices-base (min 0.5x / max 1.45x Basis) ist die fruehere
    // GOLD_NUGGET-Luecke geschlossen: auch an den unabhaengigen Extremen kein Gewinn.
    assertTrue(failures.isEmpty(),String.join("\n",failures));
  }
  @Test void multiStepAllKnownRecipesRespectCostCap() {
    var recipes=RECIPES.stream().map(r->new PriceCatalog.CatalogRecipe(r.output(),r.outputs(),Collections.nCopies(r.inputs(),List.of(r.input())))).toList();
    Map<Material,Long> purchases=new EnumMap<>(Material.class);
    for(var r:RECIPES) purchases.put(r.input(),1000L);
    purchases.put(Material.GOLD_BLOCK,1350L); purchases.put(Material.WHEAT,9L);
    var costs=RecipeCost.derive(purchases,recipes);
    assertEquals(1350.0/81,costs.get(Material.GOLD_NUGGET),1e-8);
    assertTrue(costs.get(Material.BREAD)<=27.0);
    for(var r:recipes) { double in=0;for(var options:r.ingredients())in+=costs.get(options.get(0)); assertTrue(costs.get(r.output())*r.amount()<=in+1e-7,r.toString()); }
  }
  @Test void recipesAndDiamondRatio() {
    var cfg=load("config.yml");
    var recipes=RECIPES.stream().map(r -> new PriceCatalog.CatalogRecipe(r.output(),r.outputs(),Collections.nCopies(r.inputs(),List.of(r.input())))).toList();
    Set<Material> materials=EnumSet.noneOf(Material.class); RECIPES.forEach(r -> { materials.add(r.input()); materials.add(r.output()); });
    var catalog=load("prices-base.yml");
    for(String key:catalog.getConfigurationSection("base").getKeys(false)) if(!materials.contains(Material.matchMaterial(key))) catalog.set("base."+key,null);
    var prices=PriceCatalog.build(catalog,recipes,materials::contains,m -> m == Material.BEEF ? "nahrung" : m == Material.RAW_IRON_BLOCK ? "bloecke" : "materialien");
    List<String> failures=new ArrayList<>();
    for(var r:RECIPES) {
      assertTrue(prices.containsKey(r.input()) && prices.containsKey(r.output()),r.toString());
      if(Market.unbuyable(cfg,r.input()) || cfg.getStringList("market.unsellable").contains(r.output().name())) continue;
      var in=Market.quote(cfg,r.input(),prices.get(r.input())); var out=Market.quote(cfg,r.output(),prices.get(r.output()));
      for(boolean worst:List.of(false,true)) {
        double buy=worst?in.min():PriceModel.recover(in.base(),in.base(),in.min(),in.max(),0,0);
        double sell=worst?out.max():PriceModel.recover(out.base(),out.base(),out.min(),out.max(),0,0);
        long cost=r.inputs()*Market.cents(cfg,r.input(),buy,true), revenue=r.outputs()*Market.cents(cfg,r.output(),sell,false);
        if(cost<revenue) failures.add(r+" "+(worst?"worst":"base")+" cost="+cost+" revenue="+revenue);
      }
    }
    var diamond=Market.quote(cfg,Material.DIAMOND,prices.get(Material.DIAMOND)); var iron=Market.quote(cfg,Material.IRON_INGOT,prices.get(Material.IRON_INGOT));
    assertTrue(Market.cents(cfg,Material.DIAMOND,diamond.base(),false)>=20*Market.cents(cfg,Material.IRON_INGOT,iron.base(),false));
    assertTrue(failures.isEmpty(),String.join("\n",failures));
  }
}
