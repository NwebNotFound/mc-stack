package de.mcstack.craftcore;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SellPricingTest {
  private final SellPricing.Config config = new SellPricing.Config(100, Map.of(), 500, 0, null);
  private SellPricing.Enchant enchant(String key, int level) { return new SellPricing.Enchant(key, level, false); }
  @Test void plainItem() {
    assertEquals(new SellPricing.Breakdown(500, 0, 0, 0, 500), SellPricing.calculate(500,List.of(),0,0,config));
  }
  @Test void enchantedSwordMultipleLevels() {
    assertEquals(new SellPricing.Breakdown(500, 0, 700, 500, 1000), SellPricing.calculate(500,List.of(enchant("sharpness",5),enchant("unbreaking",2)),0,100,config));
  }
  @Test void override() {
    var cfg=new SellPricing.Config(100,Map.of("sharpness",250L),500,0,null);
    assertEquals(500,SellPricing.calculate(500,List.of(enchant("minecraft:sharpness",2)),0,100,cfg).enchantBonus());
  }
  @Test void cursesGiveNoBonus() {
    var enchants=List.of(enchant("minecraft:binding_curse",1),enchant("vanishing_curse",1),new SellPricing.Enchant("other",3,true));
    assertEquals(new SellPricing.Breakdown(500,0,0,0,500),SellPricing.calculate(500,enchants,0,100,config));
  }
  @Test void damageScalesOnlyBase() {
    assertEquals(new SellPricing.Breakdown(500,125,200,200,575),SellPricing.calculate(500,List.of(enchant("sharpness",2)),25,100,config));
    assertEquals(0,SellPricing.calculate(100,List.of(),100,100,config).total());
  }
  @Test void minimumFactor() {
    var cfg=new SellPricing.Config(100,Map.of(),500,0.2,null);
    assertEquals(new SellPricing.Breakdown(500,400,0,0,100),SellPricing.calculate(500,List.of(),100,100,cfg));
  }
  @Test void tippedArrowUsesOwnBaseOrOverride() {
    assertEquals(300,SellPricing.base(300,true,config));
    var cfg=new SellPricing.Config(100,Map.of(),500,0,75L);
    assertEquals(75,SellPricing.calculate(SellPricing.base(300,true,cfg),List.of(),0,0,cfg).total());
    assertEquals(300,SellPricing.base(300,false,cfg));
  }
  @Test void enchantedBookHasNoDurabilityReduction() {
    assertEquals(new SellPricing.Breakdown(250,0,300,300,550),SellPricing.calculate(250,List.of(enchant("mending",1),enchant("efficiency",2)),0,0,config));
  }
}
