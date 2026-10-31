package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuReturnTest {
  @Test void fullInventoryReturnsAllOnCloseQuitAndShutdown() {
    for(String event:List.of("close","quit","shutdown")) {
      List<Integer> inventory=new ArrayList<>(), abholfach=new ArrayList<>();
      List<Integer> held=List.of(64,17,3);
      MenuReturn.giveBack(held,item->List.of(item),abholfach::add);
      assertEquals(held.stream().mapToInt(Integer::intValue).sum(),
          inventory.stream().mapToInt(Integer::intValue).sum()+abholfach.stream().mapToInt(Integer::intValue).sum(),event);
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
