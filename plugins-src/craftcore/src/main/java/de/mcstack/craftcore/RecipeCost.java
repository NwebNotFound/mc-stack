package de.mcstack.craftcore;

import java.util.*;
import org.bukkit.Material;

/** Conservative fractional lower bounds for any sequence of registered recipes. */
final class RecipeCost {
  static Map<Material,Double> derive(Map<Material,Long> purchases, List<PriceCatalog.CatalogRecipe> recipes) {
    Map<Material,Double> costs=new EnumMap<>(Material.class);
    purchases.forEach((material, cents)->costs.put(material,cents.doubleValue()));
    for(int pass=0;pass<Material.values().length;pass++) {
      boolean changed=false;
      for(var recipe:recipes) {
        if(recipe.amount()<=0 || recipe.ingredients().isEmpty()) continue;
        double total=0;
        for(var options:recipe.ingredients()) {
          double cheapest=Double.POSITIVE_INFINITY;
          for(var option:options) cheapest=Math.min(cheapest,costs.getOrDefault(option,Double.POSITIVE_INFINITY));
          total+=cheapest;
        }
        double cost=total/recipe.amount();
        if(Double.isFinite(cost) && cost<costs.getOrDefault(recipe.output(),Double.POSITIVE_INFINITY)-1e-9) {
          costs.put(recipe.output(),cost); changed=true;
        }
      }
      if(!changed) break;
    }
    return costs;
  }
  private RecipeCost() {}
}
