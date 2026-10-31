package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SellGuiTest {
  @Test void pricesIncludeStackAndDurability() {
    long unit=Market.durabilityPrice(100,25,100);
    assertEquals(75,unit);
    assertEquals(4800,Market.total(unit,64));
  }
  @Test void previewFormatsUnitAndStackInCents() {
    assertEquals("STONE: 0.75 CP / Stueck, 48.00 CP / Stack",Market.sellPreview("STONE",75,64));
    assertEquals("STONE: 0.01 CP / Stueck, 0.01 CP / Stack",Market.sellPreview("STONE",1,1));
  }
  @Test void asyncConfigIsAnImmutableSnapshot() {
    var cfg=new org.bukkit.configuration.MemoryConfiguration();
    cfg.set("market.buyback_caps.enabled",true);
    cfg.set("market.buyback_caps.items.STONE.max-price",1.25);
    cfg.set("market.buyback_caps.items.STONE.max-per-day",8);
    cfg.set("market.reset-hour",5);
    var snapshot=Market.sellConfig(cfg,org.bukkit.Material.STONE,200,100,90,120,2.0,150000);
    cfg.set("market.buyback_caps.items.STONE.max-price",0.01);
    cfg.set("market.buyback_caps.items.STONE.max-per-day",1);
    cfg.set("market.reset-hour",12);
    assertEquals(125,snapshot.maxSell()); assertEquals(8,snapshot.perDay()); assertEquals(5,snapshot.resetHour());
  }
  @Test void dailyLimitLeavesUnsoldItems() {
    assertEquals(3,Market.affordable(375,100,8));
    assertEquals(0,Market.affordable(99,100,8));
    assertEquals(8,Market.affordable(800,100,8));
  }
  @Test void returnAmountIsRemainderAfterAcceptedUnits() {
    String[] slots={"stone", null, "iron"};
    java.util.List<String> delivered=new java.util.ArrayList<>();
    Market.drain(slots.length,i->slots[i],i->slots[i]=null,item->{
      assertTrue(java.util.Arrays.stream(slots).noneMatch(item::equals));
      delivered.add(item);
    });
    Market.drain(slots.length,i->slots[i],i->slots[i]=null,delivered::add);
    assertEquals(java.util.List.of("stone","iron"),delivered);
  }
}
