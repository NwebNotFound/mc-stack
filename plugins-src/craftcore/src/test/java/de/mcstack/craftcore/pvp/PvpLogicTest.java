package de.mcstack.craftcore.pvp;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PvpLogicTest {
 @Test void names() { assertTrue(PvpLogic.validName("a_1-x")); assertFalse(PvpLogic.validName("../x")); assertFalse(PvpLogic.validName("A")); assertFalse(PvpLogic.validName("a".repeat(33))); }
 @Test void regionAndRoundtrip() throws Exception {
  var r=PvpLogic.Region.between(new PvpLogic.Point(5,8,9),new PvpLogic.Point(1,2,3));
  assertEquals(new PvpLogic.Point(1,2,3),r.min()); assertTrue(r.contains(new PvpLogic.Point(5,8,9))); assertFalse(r.contains(new PvpLogic.Point(6,8,9))); assertEquals(new PvpLogic.Point(4,6,6),r.relative(new PvpLogic.Point(5,8,9)));
  var arena=new PvpLogic.Arena(r,Map.of(1,new PvpLogic.Spawn(1.5,2,3,45,5),2,new PvpLogic.Spawn(2,3,4,0,0))); var y=new YamlConfiguration(); arena.write(y); var copy=new YamlConfiguration(); copy.loadFromString(y.saveToString()); assertEquals(arena,PvpLogic.Arena.read(copy));
 }
 @Test void kitsChooseEitherSelectedKit() {
  for (int choice = 0; choice < 2; choice++) {
   final int selected = choice;
   Random stub = new Random() { @Override public int nextInt(int bound) { assertEquals(2, bound); return selected; } };
   assertEquals(List.of("a", "b", choice == 0 ? "a" : "b"), PvpLogic.kits("a", "b", stub));
  }
 }
 @Test void matches() { assertEquals(PvpLogic.Result.PENDING,PvpLogic.result(List.of(PvpLogic.Result.A,PvpLogic.Result.DRAW))); assertEquals(PvpLogic.Result.A,PvpLogic.result(List.of(PvpLogic.Result.A,PvpLogic.Result.DRAW,PvpLogic.Result.A))); assertEquals(PvpLogic.Result.DRAW,PvpLogic.result(List.of(PvpLogic.Result.A,PvpLogic.Result.B,PvpLogic.Result.DRAW))); }
}
