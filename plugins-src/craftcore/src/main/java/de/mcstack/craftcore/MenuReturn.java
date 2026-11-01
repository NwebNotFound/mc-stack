package de.mcstack.craftcore;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.IntFunction;
import java.util.function.IntConsumer;

/** Transfers each item to inventory first and sends only the remainder to overflow. */
final class MenuReturn {
  /** Owns return callbacks until close, quit, or shutdown; each runs at most once. */
  static final class Registry<K> {
    private record Entry(Object menu, Runnable giveBack, Runnable close) {}
    private final Map<K,Entry> entries=new HashMap<>();
    void register(K owner,Object menu,Runnable giveBack,Runnable close){
      finish(owner); entries.put(owner,new Entry(menu,giveBack,close));
    }
    <T> void registerHeld(K owner,Object menu,int[] slots,IntFunction<T> get,IntConsumer clear,
                          Target<T> target,Overflow<T> overflow,Runnable reset,Runnable close) {
      register(owner,menu,()->{
        for(int slot:slots) {
          T item=get.apply(slot);
          if(item!=null) { giveBack(List.of(item),target,overflow); clear.accept(slot); }
        }
        reset.run();
      },close);
    }
    void finish(K owner,Object menu){Entry entry=entries.get(owner);if(entry!=null && entry.menu()==menu)finish(owner);}
    void finish(K owner){Entry entry=entries.remove(owner);if(entry!=null)entry.giveBack().run();}
    void shutdown(){for(K owner:new ArrayList<>(entries.keySet())){Entry entry=entries.remove(owner);if(entry!=null){entry.giveBack().run();entry.close().run();}}}
  }
  interface Target<T> { List<T> add(T item); }
  interface Overflow<T> { void accept(T item); }
  static <T> void giveBack(Iterable<T> items, Target<T> target, Overflow<T> overflow) {
    for(T item:items) for(T remaining:target.add(item)) overflow.accept(remaining);
  }
}
