package de.mcstack.craftcore.pvp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaEditorTest {
    static class Store implements ArenaEditor.Storage {
        final Map<String,ArenaEditor.Draft> drafts=new TreeMap<>(), saved=new TreeMap<>();
        static ArenaEditor.Draft copy(ArenaEditor.Draft d) { var c=new ArenaEditor.Draft(); c.region=d.region; c.spawns.putAll(d.spawns); return c; }
        public Set<String> savedNames() { return saved.keySet(); }
        public Map<String,ArenaEditor.Draft> loadDrafts() { Map<String,ArenaEditor.Draft> c=new TreeMap<>(); drafts.forEach((n,d)->c.put(n,copy(d))); return c; }
        public ArenaEditor.Draft loadSaved(String n) { return copy(saved.get(n)); }
        public void writeDraft(String n,ArenaEditor.Draft d) { drafts.put(n,copy(d)); }
        public void deleteDraft(String n) { drafts.remove(n); }
        public void saveArena(String n,ArenaEditor.Draft d) { saved.put(n,copy(d)); }
    }
    static class Context implements ArenaEditor.Context {
        ArenaEditor.Position p=new ArenaEditor.Position("pvp_build",1,2,3,0,0);
        PvpLogic.Region selection;
        public String id() { return "player"; }
        public ArenaEditor.Position position() { return p; }
        public PvpLogic.Region worldEditSelection() { return selection; }
    }
    @Test void fullFlowAndRestart() throws Exception {
        var store=new Store(); var ctx=new Context(); var editor=new ArenaEditor(store);
        editor.execute(ctx,new String[]{"create","colosseum"});
        assertTrue(editor.execute(ctx,new String[]{"pos1","colosseum"}).contains("Position 1"));
        ctx.p=new ArenaEditor.Position("pvp_build",5,6,7,0,0);
        editor.execute(ctx,new String[]{"pos2"}); editor.execute(ctx,new String[]{"region","colosseum"});
        editor.execute(ctx,new String[]{"setspawn","colosseum","1"});
        editor=new ArenaEditor(store);
        assertTrue(editor.execute(ctx,new String[]{"info","colosseum"}).contains("Spawns: [1]"));
        editor.execute(ctx,new String[]{"setspawn","colosseum","2"});
        assertTrue(editor.execute(ctx,new String[]{"save","colosseum"}).contains("gespeichert"));
        assertFalse(store.drafts.containsKey("colosseum")); assertTrue(store.saved.containsKey("colosseum"));
        assertTrue(editor.execute(ctx,new String[]{"info","colosseum"}).contains("gespeichert: ja"));
        assertThrows(IllegalArgumentException.class,()->new ArenaEditor(store).execute(ctx,new String[]{"remove","colosseum"}));
        assertTrue(store.saved.containsKey("colosseum"));
    }
    @Test void seededYamlDraftLoadsAndIsDeletedAfterSave(@TempDir Path root) throws Exception {
        Path arenas=Files.createDirectory(root.resolve("arenas"));
        Path drafts=Files.createDirectory(root.resolve("drafts"));
        Path seed=drafts.resolve("colosseum.yml");
        Files.writeString(seed, """
                min:
                  x: -18
                  y: 0
                  z: 18
                max:
                  x: 18
                  y: 15
                  z: -18
                spawns: {}
                """);
        var storage=new PvpModule.EditorStorage(arenas.toFile(),drafts.toFile(),(x,y,z)->"minecraft:air");
        var editor=new ArenaEditor(storage); var ctx=new Context();
        assertTrue(editor.hasDrafts());
        assertTrue(editor.execute(ctx,new String[]{"info","colosseum"}).contains("Spawns: keine"));
        assertThrows(IllegalArgumentException.class,()->new ArenaEditor(storage).execute(ctx,new String[]{"save","colosseum"}));
        assertTrue(Files.exists(seed));
        ctx.p=new ArenaEditor.Position("pvp_build",-10,1,0,0,0);
        editor.execute(ctx,new String[]{"setspawn","colosseum","1"});
        ctx.p=new ArenaEditor.Position("pvp_build",10,1,0,0,0);
        editor.execute(ctx,new String[]{"setspawn","colosseum","2"});
        editor=new ArenaEditor(storage);
        assertTrue(editor.execute(ctx,new String[]{"save","colosseum"}).contains("gespeichert"));
        assertTrue(Files.exists(arenas.resolve("colosseum.yml")));
        assertFalse(Files.exists(seed));
        assertFalse(editor.hasDrafts());
    }
    @Test void worldEditAndMessages() throws Exception {
        var store=new Store(); var ctx=new Context(); var editor=new ArenaEditor(store);
        editor.execute(ctx,new String[]{"create","arena"});
        ctx.p=new ArenaEditor.Position("world",0,0,0,0,0);
        assertEquals("Nur in pvp_build verfügbar – du bist gerade in world. Nutze /pvp build.",assertThrows(IllegalArgumentException.class,()->editor.execute(ctx,new String[]{"region","arena"})).getMessage());
        ctx.p=new ArenaEditor.Position("pvp_build",0,0,0,0,0);
        assertTrue(assertThrows(IllegalArgumentException.class,()->editor.execute(ctx,new String[]{"region","arena"})).getMessage().contains("WorldEdit"));
        ctx.selection=PvpLogic.Region.between(new PvpLogic.Point(0,0,0),new PvpLogic.Point(10,10,10));
        editor.execute(ctx,new String[]{"region","arena"}); assertEquals(ctx.selection,store.drafts.get("arena").region);
        assertEquals(ArenaEditor.COMMANDS,editor.execute(ctx,new String[]{"unknown"}));
    }
}
