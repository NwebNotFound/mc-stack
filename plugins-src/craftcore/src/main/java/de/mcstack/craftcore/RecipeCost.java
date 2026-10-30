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
          costs.put(recipe.output(),cost); VIA.put(recipe.output(),recipe); changed=true;
        }
      }
      if(!changed) break;
    }
    return costs;
  }
  static final Map<Material,PriceCatalog.CatalogRecipe> VIA=new java.util.concurrent.ConcurrentHashMap<>();
  static String path(Material m){ StringBuilder b=new StringBuilder(); Set<Material> seen=EnumSet.noneOf(Material.class); Material cur=m;
    while(cur!=null && seen.add(cur)){ var r=VIA.get(cur); if(r==null){b.append(cur).append("[Kauf]");break;} b.append(cur).append("<-").append(r.amount()).append("x/"); Material next=null; for(var o:r.ingredients()) if(!o.isEmpty()){next=o.get(0); b.append(o.size()>3?o.subList(0,3)+"...":o); break;} b.append(" "); cur=next; }
    return b.toString(); }
  private RecipeCost() {}
}
