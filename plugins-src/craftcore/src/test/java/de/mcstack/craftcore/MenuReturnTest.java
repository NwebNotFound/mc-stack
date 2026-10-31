package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuReturnTest {
  @Test void fullInventoryReturnsAllOnCloseQuitAndShutdown() {
    for(String event:List.of("close","quit","shutdown")) {
      List<Integer> inventory=new ArrayList<>(), abholfach=new ArrayList<>(), held=new ArrayList<>(List.of(64,17,3));
      int[] closed={0}; Object menu=new Object(); MenuReturn.Registry<String> registry=new MenuReturn.Registry<>();
      registry.registerHeld("player",menu,new int[]{0,1,2},held::get,slot->held.set(slot,null),item->List.of(item),abholfach::add,()->{},()->closed[0]++);
      if(event.equals("close"))registry.finish("player",menu); else if(event.equals("quit"))registry.finish("player"); else registry.shutdown();
      registry.finish("player",menu);
      assertEquals(java.util.Arrays.asList(null,null,null),held); assertEquals(List.of(64,17,3),abholfach); assertEquals(84,inventory.stream().mapToInt(Integer::intValue).sum()+abholfach.stream().mapToInt(Integer::intValue).sum(),event);
      assertEquals(event.equals("shutdown")?1:0,closed[0]);
    }
  }

  @Test void onlyRemainderGoesToOverflow() {
    List<Integer> inventory=new ArrayList<>(), overflow=new ArrayList<>();
    MenuReturn.giveBack(List.of(12),item->{inventory.add(5);return List.of(item-5);},overflow::add);
    assertEquals(List.of(5),inventory);assertEquals(List.of(7),overflow);
  }
  @Test void editPriceControlsUseExpectedSlots() {
    assertEquals(-1000,Shop.editDelta(9));assertEquals(-100,Shop.editDelta(10));assertEquals(-10,Shop.editDelta(11));
    assertEquals(10,Shop.editDelta(13));assertEquals(100,Shop.editDelta(14));assertEquals(1000,Shop.editDelta(15));assertEquals(0,Shop.editDelta(12));
  }
}
