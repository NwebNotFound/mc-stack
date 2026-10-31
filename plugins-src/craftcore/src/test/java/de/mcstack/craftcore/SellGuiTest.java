package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SellGuiTest {
  @Test void pricesIncludeStackAndDurability() {
    long unit=Market.durabilityPrice(100,25,100);
    assertEquals(75,unit);
    assertEquals(4800,Market.total(unit,64));
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
