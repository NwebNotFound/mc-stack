package de.mcstack.craftcore;

import java.io.File;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;

final class PriceCatalog {
  static boolean eligible(Material m) { return eligible(null, m); }
  static boolean eligible(org.bukkit.configuration.Configuration cfg, Material m) { return m != null && SurvivalItems.obtainable(cfg, m); }
  static Map<Material,Double> build(CraftCore core) {
    File file=new File(core.getDataFolder(),"prices-base.yml"); if(!file.exists()) core.saveResource("prices-base.yml",false);
    YamlConfiguration cfg=YamlConfiguration.loadConfiguration(file);
    List<Recipe> recipes=new ArrayList<>(); Bukkit.recipeIterator().forEachRemaining(recipes::add);
    return build(cfg, recipes.stream().map(PriceCatalog::recipe).filter(Objects::nonNull).toList(), m -> m.isItem() && eligible(core.getConfig(), m), PriceCatalog::category);
  }
  record CatalogRecipe(Material output, int amount, List<List<Material>> ingredients, boolean cooking) {
    CatalogRecipe(Material output, int amount, List<List<Material>> ingredients) { this(output,amount,ingredients,false); }
  }
  // Only explicit prices and recipes whose ingredients are themselves known count.
  // Category fallback values are deliberately excluded from this provenance set.
  static Set<Material> known(YamlConfiguration cfg, List<CatalogRecipe> recipes) {
    return known(cfg,recipes,PriceCatalog::eligible);
  }
  static Set<Material> known(YamlConfiguration cfg, List<CatalogRecipe> recipes, java.util.function.Predicate<Material> eligible) {
    Set<Material> known=EnumSet.noneOf(Material.class);
    var section=cfg.getConfigurationSection("base");
    if(section!=null) for(String key:section.getKeys(false)) { Material m=Material.matchMaterial(key); if(m!=null && eligible.test(m)) known.add(m); }
    boolean changed;
    do { changed=false; for(CatalogRecipe r:recipes) {
      if(!eligible.test(r.output()) || r.amount()<=0 || r.ingredients().isEmpty()) continue;
      if(r.ingredients().stream().allMatch(options->options.stream().anyMatch(known::contains))) changed |= known.add(r.output());
    }} while(changed);
    return known;
  }
  static CatalogRecipe recipe(Recipe recipe) {
    List<RecipeChoice> ingredients=new ArrayList<>();
    if(recipe instanceof ShapedRecipe shaped) { for(String row:shaped.getShape()) for(char ch:row.toCharArray()) if(ch!=' ') ingredients.add(shaped.getChoiceMap().get(ch)); }
    else if(recipe instanceof ShapelessRecipe shapeless) ingredients.addAll(shapeless.getChoiceList());
    else if(recipe instanceof CookingRecipe<?> cooking) ingredients.add(cooking.getInputChoice());
    else if(recipe instanceof StonecuttingRecipe stone) ingredients.add(stone.getInputChoice());
    else return null;
    List<List<Material>> choices=new ArrayList<>();
    for(RecipeChoice choice:ingredients) { List<Material> options=new ArrayList<>(); if(choice instanceof RecipeChoice.MaterialChoice mc) options.addAll(mc.getChoices()); else if(choice instanceof RecipeChoice.ExactChoice ec) for(ItemStack stack:ec.getChoices()) options.add(stack.getType()); choices.add(options); }
    return new CatalogRecipe(recipe.getResult().getType(),recipe.getResult().getAmount(),choices,recipe instanceof CookingRecipe<?>);
  }
  static Map<Material,Double> build(YamlConfiguration cfg, List<CatalogRecipe> recipes) {
    return build(cfg,recipes,PriceCatalog::eligible,PriceCatalog::category);
  }
  static Map<Material,Double> build(YamlConfiguration cfg, List<CatalogRecipe> recipes, java.util.function.Predicate<Material> eligible, java.util.function.Function<Material,String> categories) {
    Map<Material,Double> prices=new EnumMap<>(Material.class); Set<Material> fixed=EnumSet.noneOf(Material.class);
    var section=cfg.getConfigurationSection("base");
    if(section!=null) for(String key:section.getKeys(false)) { Material m=Material.matchMaterial(key); double v=section.getDouble(key); if(m==null || !Double.isFinite(v) || v<0.01) throw new IllegalArgumentException("Ungueltiger Basispreis: "+key); if(!eligible.test(m)) continue; prices.put(m,v); fixed.add(m); }
    Set<Material> outputs=EnumSet.noneOf(Material.class);
    for(CatalogRecipe r:recipes) outputs.add(r.output());
    for(Material m:Material.values()) if(eligible.test(m) && !prices.containsKey(m) && !outputs.contains(m)) { double value=cfg.getDouble("fallback."+categories.apply(m),cfg.getDouble("fallback.default",10)); if(!Double.isFinite(value)||value<.01) throw new IllegalArgumentException("Ungueltiger Fallback: "+m); prices.put(m,value); fixed.add(m); }
    // Repeated relaxation resolves recipe chains independent of recipe iteration order.
    for(int pass=0;pass<Material.values().length;pass++) { boolean changed=false;
      for(CatalogRecipe recipe:recipes) { Material out=recipe.output(); if(!eligible.test(out) || fixed.contains(out))continue;
        if(recipe.ingredients().isEmpty())continue; double sum=0; boolean valid=true;
        for(List<Material> options:recipe.ingredients()) {
          double best=Double.POSITIVE_INFINITY; for(Material m:options) if(prices.containsKey(m)) best=Math.min(best,prices.get(m)); if(!Double.isFinite(best)){valid=false;break;} sum+=best;
        }
        if(valid) { double value=Math.max(.01,Math.min(100000000, sum*1.1/Math.max(1,recipe.amount()))); Double old=prices.get(out); if(old==null || value<old-0.000001){prices.put(out,value);changed=true;} }
      }
      if(!changed)break;
    }
    // Zyklen wie RAW_IRON <-> RAW_IRON_BLOCK haben sonst keinen Preis und fielen auf den billigen Kategorie-Fallback
    // (Block 3 CP fuer 9 Raw Iron) - das drueckte ueber die Rezeptkostengrenze den Ankauf von Eisen/Gold.
    // Unbepreiste Schmelz-Zutaten erben den Preis ihres Schmelzprodukts, danach wird erneut relaxiert.
    boolean derived=false;
    for(CatalogRecipe r:recipes) if(r.cooking() && prices.containsKey(r.output()) && r.amount()>0)
      for(List<Material> options:r.ingredients()) for(Material in:options)
        if(eligible.test(in) && !prices.containsKey(in)) { prices.put(in,prices.get(r.output())/r.amount()); derived=true; }
    if(derived) for(int pass=0;pass<Material.values().length;pass++) { boolean changed=false;
      for(CatalogRecipe recipe:recipes) { Material out=recipe.output(); if(!eligible.test(out) || fixed.contains(out) || recipe.ingredients().isEmpty())continue;
        double sum=0; boolean valid=true;
        for(List<Material> options:recipe.ingredients()) { double best=Double.POSITIVE_INFINITY; for(Material m:options) if(prices.containsKey(m)) best=Math.min(best,prices.get(m)); if(!Double.isFinite(best)){valid=false;break;} sum+=best; }
        if(valid) { double value=Math.max(.01,Math.min(100000000, sum*1.1/Math.max(1,recipe.amount()))); Double old=prices.get(out); if(old==null){prices.put(out,value);changed=true;} }
      }
      if(!changed)break;
    }
    for(Material m:Material.values()) if(eligible.test(m) && !prices.containsKey(m)) { double value=cfg.getDouble("fallback."+categories.apply(m),cfg.getDouble("fallback.default",10)); if(!Double.isFinite(value)||value<.01) throw new IllegalArgumentException("Ungueltiger Fallback: "+m); prices.put(m,value); }
    return prices;
  }
  static String category(Material m) { String n=m.name();
    if(n.endsWith("_SWORD")||n.endsWith("_AXE")||n.endsWith("_PICKAXE")||n.endsWith("_SHOVEL")||n.endsWith("_HOE")||n.endsWith("_HELMET")||n.endsWith("_CHESTPLATE")||n.endsWith("_LEGGINGS")||n.endsWith("_BOOTS")||n.contains("BOW")||n.equals("SHIELD"))return "ausruestung";
    if(n.contains("FOOD")||n.contains("BEEF")||n.contains("PORK")||n.contains("CHICKEN")||n.contains("MUTTON")||n.contains("RABBIT")||n.contains("FISH")||n.contains("SALMON")||n.contains("COD")||n.contains("APPLE")||n.contains("BREAD")||n.contains("STEW"))return "nahrung";
    if(n.contains("REDSTONE")||n.contains("PISTON")||n.contains("REPEATER")||n.contains("COMPARATOR")||n.contains("OBSERVER")||n.contains("HOPPER")||n.contains("RAIL"))return "technik";
    if(m.isBlock())return "bloecke"; return "materialien";
  }
}
