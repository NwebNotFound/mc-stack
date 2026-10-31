package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcLifecycleTest {
  static class Npc {
    final int id; final String uuid; final String key; final boolean tagged;
    boolean spawned=true;
    Npc(int id,String uuid,String key,boolean tagged) { this.id=id; this.uuid=uuid; this.key=key; this.tagged=tagged; }
  }
  static class Registry implements NpcLifecycle.Registry<Npc> {
    final List<Npc> owned=new ArrayList<>(), defaults=new ArrayList<>();
    int creates;
    public List<Npc> owned() { return new ArrayList<>(owned); }
    public List<Npc> defaults() { return new ArrayList<>(defaults); }
    public int id(Npc npc) { return npc.id; }
    public String uuid(Npc npc) { return npc.uuid; }
    public boolean tagged(Npc npc) { return npc.tagged; }
    public void remove(Npc npc) { owned.remove(npc); defaults.remove(npc); }
    public void create(String key) { creates++; owned.add(new Npc(creates,"new-"+creates,key,true)); }
  }
  @Test void restartsReplaceOwnedNpcsOncePerDefinition() {
    Registry registry=new Registry();
    List<String> keys=List.of("one","two");
    assertEquals(new NpcLifecycle.Result(0,2),new NpcLifecycle<Npc>().activate(registry,keys,List.of()));
    assertEquals(new NpcLifecycle.Result(2,2),new NpcLifecycle<Npc>().activate(registry,keys,List.of()));
    for(String key:keys) assertEquals(1,registry.owned.stream().filter(n -> n.key.equals(key)).count());
  }
  @Test void entityDespawnAndRespawnDoesNotCreateNpc() {
    Registry registry=new Registry();
    new NpcLifecycle<Npc>().activate(registry,List.of("one"),List.of());
    Npc npc=registry.owned.get(0);
    npc.spawned=false; npc.spawned=true;
    assertSame(npc,registry.owned.get(0));
    assertEquals(1,registry.creates);
  }
  @Test void lateEnableAfterFallbackDoesNothing() {
    Registry registry=new Registry(); NpcLifecycle<Npc> lifecycle=new NpcLifecycle<>();
    assertEquals(new NpcLifecycle.Result(0,1),lifecycle.activate(registry,List.of("one"),List.of()));
    assertEquals(new NpcLifecycle.Result(0,0),lifecycle.activate(registry,List.of("one"),List.of()));
    assertEquals(1,registry.creates);
  }
  @Test void removesOnlyMatchingLegacyOrTaggedDefaults() {
    Registry registry=new Registry();
    Npc byId=new Npc(7,"id-match","legacy",false), byUuid=new Npc(8,"uuid-match","legacy",false);
    Npc tagged=new Npc(9,"tagged","legacy",true), unrelated=new Npc(10,"other","legacy",false);
    registry.defaults.addAll(List.of(byId,byUuid,tagged,unrelated));
    var result=new NpcLifecycle<Npc>().activate(registry,List.of("one"),List.of(new NpcLifecycle.LegacyId(7,"uuid-match")));
    assertEquals(new NpcLifecycle.Result(3,1),result);
    assertEquals(List.of(unrelated),registry.defaults);
  }
}
