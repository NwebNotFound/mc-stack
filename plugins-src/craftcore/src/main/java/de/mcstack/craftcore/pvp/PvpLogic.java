package de.mcstack.craftcore.pvp;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

public final class PvpLogic {
    private PvpLogic() {}
    public static boolean validName(String name) { return name != null && name.matches("[a-z0-9_-]{1,32}"); }
    public record Point(int x, int y, int z) {}
    public record Region(Point min, Point max) {
        public static Region between(Point a, Point b) { return new Region(new Point(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.min(a.z,b.z)), new Point(Math.max(a.x,b.x), Math.max(a.y,b.y), Math.max(a.z,b.z))); }
        public boolean contains(Point p) { return p.x>=min.x && p.x<=max.x && p.y>=min.y && p.y<=max.y && p.z>=min.z && p.z<=max.z; }
        public Point relative(Point p) { return new Point(p.x-min.x,p.y-min.y,p.z-min.z); }
    }
    public record Spawn(double x, double y, double z, float yaw, float pitch) {}
    public record Arena(Region region, Map<Integer, Spawn> spawns) {
        public void write(ConfigurationSection s) {
            s.set("min.x",region.min.x); s.set("min.y",region.min.y); s.set("min.z",region.min.z);
            s.set("max.x",region.max.x); s.set("max.y",region.max.y); s.set("max.z",region.max.z);
            spawns.forEach((n,p)->{ String k="spawns."+n+"."; s.set(k+"x",p.x);s.set(k+"y",p.y);s.set(k+"z",p.z);s.set(k+"yaw",p.yaw);s.set(k+"pitch",p.pitch); });
        }
        public static Arena read(ConfigurationSection s) {
            Region r=Region.between(new Point(s.getInt("min.x"),s.getInt("min.y"),s.getInt("min.z")),new Point(s.getInt("max.x"),s.getInt("max.y"),s.getInt("max.z")));
            Map<Integer,Spawn> spawns=new TreeMap<>(); ConfigurationSection sec=s.getConfigurationSection("spawns");
            if(sec!=null) for(String key:sec.getKeys(false)){ String k="spawns."+key+"."; spawns.put(Integer.parseInt(key),new Spawn(s.getDouble(k+"x"),s.getDouble(k+"y"),s.getDouble(k+"z"),(float)s.getDouble(k+"yaw"),(float)s.getDouble(k+"pitch"))); }
            return new Arena(r,spawns);
        }
    }
    public static List<String> kits(String a,String b,Random random) { return List.of(a,b,random.nextInt(2)==0 ? a : b); }
    public enum Result { A, B, DRAW, PENDING }
    public static Result result(List<Result> rounds) {
        if(rounds.size()>3 || rounds.stream().anyMatch(r->r==Result.PENDING)) throw new IllegalArgumentException();
        int a=0,b=0; for(Result r:rounds) { if(r==Result.A) a++; if(r==Result.B) b++; if(a==2) return Result.A; if(b==2) return Result.B; }
        return rounds.size()==3 ? Result.DRAW : Result.PENDING;
    }
}
