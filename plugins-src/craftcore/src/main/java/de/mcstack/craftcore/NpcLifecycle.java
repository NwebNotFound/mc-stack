package de.mcstack.craftcore;

import java.util.Collection;
import java.util.List;

/** Coordinates one activation independently of Citizens and the server. */
final class NpcLifecycle<T> {
  interface Registry<T> {
    List<T> owned();
    List<T> defaults();
    int id(T npc);
    String uuid(T npc);
    boolean tagged(T npc);
    void remove(T npc);
    void create(String key);
  }
  record LegacyId(Integer id, String uuid) { }
  record Result(int removed, int spawned) { }
  private boolean active;
  boolean isActive() { return active; }
  void reset() { active=false; }

  Result activate(Registry<T> registry, Collection<String> keys, Collection<LegacyId> legacy) {
    if(active) return new Result(0,0);
    active=true;
    int removed=0;
    for(T npc:registry.owned()) { registry.remove(npc); removed++; }
    for(T npc:registry.defaults()) {
      boolean matches=registry.tagged(npc) || legacy.stream().anyMatch(id ->
          (id.id()!=null && id.id()==registry.id(npc)) ||
          (id.uuid()!=null && id.uuid().equalsIgnoreCase(registry.uuid(npc))));
      if(matches) { registry.remove(npc); removed++; }
    }
    for(String key:keys) registry.create(key);
    return new Result(removed,keys.size());
  }
}
