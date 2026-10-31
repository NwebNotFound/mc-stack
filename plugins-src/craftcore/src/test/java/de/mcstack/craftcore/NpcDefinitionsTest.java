package de.mcstack.craftcore;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static de.mcstack.craftcore.NpcDefinitions.*;

class NpcDefinitionsTest {
  @Test void actions() {
    assertEquals(new Action(ActionType.SERVER,"lobby"),parseAction("server lobby"));
    assertEquals(new Action(ActionType.MENU,"bank"),parseAction("menu bank"));
    assertEquals(new Action(ActionType.COMMAND,"spawn {player}"),parseAction("command spawn {player}"));
    assertEquals(new Action(ActionType.NONE,""),parseAction("none"));
    for(String bad:List.of("", "server", "none arg", "menu two words", "command /spawn", "unknown arg")) assertThrows(IllegalArgumentException.class,()->parseAction(bad));
  }
  @Test void namesAndRoundtrip() {
    assertEquals("cool_npc",keyFromName("&aCool NPC"));
    assertEquals(32,keyFromName("a".repeat(40)).length());
    assertThrows(IllegalArgumentException.class,()->keyFromName("a".repeat(65)));
    assertThrows(IllegalArgumentException.class,()->keyFromName("&a!!!"));
    NpcDef def=new NpcDef("cool_npc","&aCool NPC","Skin","&eHallo",ActionType.COMMAND,"spawn {player}");
    assertEquals("&aCool NPC",def.displayName());
    assertEquals(def,load(def.key(),save(def)));
    assertEquals(def,find(List.of(def),"?bCOOL NPC").orElseThrow());
    assertEquals(def,find(List.of(def),"COOL_NPC").orElseThrow());
    assertThrows(IllegalArgumentException.class,()->new NpcDef("key","x".repeat(65),"","",ActionType.NONE,""));
  }
  @Test void view() {
    assertTrue(inViewAndRange(0,0,0,1,0,0,3,0,0));
    assertFalse(inViewAndRange(0,0,0,1,0,0,5,0,0));
    assertFalse(inViewAndRange(0,0,0,-1,0,0,3,0,0));
    assertFalse(inViewAndRange(0,0,0,0,0,0,3,0,0));
  }
}
