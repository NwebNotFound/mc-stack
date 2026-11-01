package de.mcstack.craftcore.pvp;

import java.io.IOException;
import java.util.*;

/** Server-independent arena editor. Persistence and external selections are supplied by the host. */
public final class ArenaEditor {
    public static final String COMMANDS = "Gültige Unterbefehle: create, pos1, pos2, region, setspawn, save, list, remove, info.";
    public record Position(String world, double x, double y, double z, float yaw, float pitch) {
        public PvpLogic.Point block() { return new PvpLogic.Point((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)); }
        public String coords() { var p=block(); return p.x()+" "+p.y()+" "+p.z(); }
    }
    // Draft YAML: optional min/max coordinates and spawns.<number> with world, x, y, z, yaw, pitch.
    public static final class Draft {
        public PvpLogic.Region region;
        public final Map<Integer, Position> spawns = new TreeMap<>();
    }
    public interface Storage {
        Set<String> savedNames();
        Map<String, Draft> loadDrafts();
        Draft loadSaved(String name);
        void writeDraft(String name, Draft draft) throws IOException;
        void deleteDraft(String name) throws IOException;
        void saveArena(String name, Draft draft) throws IOException;
    }
    public interface Context {
        String id();
        Position position();
        PvpLogic.Region worldEditSelection();
    }
    public static final class BuildWorldException extends IllegalArgumentException {
        BuildWorldException(String buildWorld, String currentWorld) { super("Nur in "+buildWorld+" verfügbar – du bist gerade in "+currentWorld+". Nutze /pvp build."); }
    }
    private final Storage storage;
    private final String buildWorld;
    private final Map<String,Draft> drafts;
    private final Map<String,Position[]> selections = new HashMap<>();
    public ArenaEditor(Storage storage, String buildWorld) { this.storage=storage; this.buildWorld=buildWorld; this.drafts=new TreeMap<>(storage.loadDrafts()); }
    public boolean hasDrafts() { return !drafts.isEmpty(); }
    public List<String> names() { Set<String> names=new TreeSet<>(storage.savedNames()); names.addAll(drafts.keySet()); return new ArrayList<>(names); }
    private Draft draft(String name) { Draft d=drafts.get(name); if(d==null && storage.savedNames().contains(name)) d=storage.loadSaved(name); return d; }
    private void requireBuild(Position p) { if(!p.world().equals(buildWorld)) throw new BuildWorldException(buildWorld,p.world()); }
    public String execute(Context context, String[] args) throws IOException {
        if(args.length==0) return COMMANDS;
        String op=args[0].toLowerCase(Locale.ROOT);
        if(!List.of("create","pos1","pos2","region","setspawn","save","list","remove","info").contains(op)) return COMMANDS;
        if(op.equals("list") && args.length==1) return "Arenen: "+String.join(", ",names());
        if(op.equals("pos1") || op.equals("pos2")) {
            if(args.length>2 || (args.length==2 && !PvpLogic.validName(args[1]))) throw new IllegalArgumentException("Name: 1–32 Zeichen a-z, 0-9, _ oder -.");
            Position p=context.position(); requireBuild(p);
            selections.computeIfAbsent(context.id(),k->new Position[2])[op.equals("pos1")?0:1]=p;
            return (op.equals("pos1")?"Position 1":"Position 2")+" gesetzt ("+p.coords()+").";
        }
        if(args.length<2 || !PvpLogic.validName(args[1])) throw new IllegalArgumentException("Name: 1–32 Zeichen a-z, 0-9, _ oder -.");
        String name=args[1];
        if(op.equals("create") && args.length==2) {
            if(names().contains(name)) throw new IllegalArgumentException("Arena existiert bereits.");
            Draft d=new Draft(); storage.writeDraft(name,d); drafts.put(name,d); return "Entwurf für "+name+" erstellt.";
        }
        if(op.equals("remove") && args.length==2) {
            if(!drafts.containsKey(name)) {
                if(storage.savedNames().contains(name)) throw new IllegalArgumentException("Gespeicherte Arena bleibt erhalten; kein Entwurf vorhanden.");
                throw new IllegalArgumentException("Arena nicht gefunden.");
            }
            storage.deleteDraft(name); drafts.remove(name); return "Entwurf für "+name+" entfernt."+(storage.savedNames().contains(name)?" Die gespeicherte Arena bleibt erhalten.":"");
        }
        Draft d=draft(name); if(d==null) throw new IllegalArgumentException("Arena nicht gefunden.");
        if(op.equals("info") && args.length==2) {
            String region=d.region==null?"nicht gesetzt":format(d.region.min())+" bis "+format(d.region.max());
            return "Arena "+name+": Region "+region+"; Spawns: "+(d.spawns.isEmpty()?"keine":d.spawns.keySet())+"; gespeichert: "+(storage.savedNames().contains(name)?"ja":"nein")+".";
        }
        if(op.equals("region") && args.length==2) {
            requireBuild(context.position()); Position[] s=selections.get(context.id());
            PvpLogic.Region region=s!=null && s[0]!=null && s[1]!=null ? PvpLogic.Region.between(s[0].block(),s[1].block()) : context.worldEditSelection();
            if(region==null) throw new IllegalArgumentException("Setze Pos1 und Pos2 mit /pvp arena pos1 und /pvp arena pos2 oder wähle eine WorldEdit-Region in "+buildWorld+" aus.");
            d.region=region; storage.writeDraft(name,d); drafts.put(name,d); return "Region für "+name+" gesetzt ("+format(region.min())+" bis "+format(region.max())+").";
        }
        if(op.equals("setspawn") && args.length==3) {
            Position p=context.position(); requireBuild(p); int n;
            try { n=Integer.parseInt(args[2]); } catch(NumberFormatException e) { throw new IllegalArgumentException("Spawn muss eine positive Zahl sein."); }
            if(n<1) throw new IllegalArgumentException("Spawn muss eine positive Zahl sein.");
            d.spawns.put(n,p); storage.writeDraft(name,d); drafts.put(name,d); return "Spawn "+n+" für "+name+" gesetzt ("+p.coords()+").";
        }
        if(op.equals("save") && args.length==2) {
            requireBuild(context.position());
            if(d.region==null || !d.spawns.containsKey(1) || !d.spawns.containsKey(2) || !d.spawns.values().stream().allMatch(p->p.world().equals(buildWorld) && d.region.contains(p.block()))) throw new IllegalArgumentException("Region und gültige Spawns 1 und 2 innerhalb der Region erforderlich.");
            storage.saveArena(name,d); storage.deleteDraft(name); drafts.remove(name); return "Arena "+name+" gespeichert.";
        }
        throw new IllegalArgumentException("Ungültiger Arena-Befehl. "+COMMANDS);
    }
    private static String format(PvpLogic.Point p) { return p.x()+" "+p.y()+" "+p.z(); }
}
