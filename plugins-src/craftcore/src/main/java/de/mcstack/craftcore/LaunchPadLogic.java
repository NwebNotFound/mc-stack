package de.mcstack.craftcore;

import java.util.*;

final class LaunchPadLogic {
  static boolean useBlockBelow(boolean feetAreAir) { return feetAreAir; }
  static int indexOf(List<Map<String, Object>> entries, String id) {
    for (int i=0;i<entries.size();i++) if (id.equalsIgnoreCase(String.valueOf(entries.get(i).get("id")))) return i;
    return -1;
  }
  static Map<String, Object> target(double x, double y, double z) {
    Map<String, Object> result=new LinkedHashMap<>(); result.put("x",x); result.put("y",y); result.put("z",z); return result;
  }
  static Map<String, Object> entry(String id, String world, int x, int y, int z, double[] target) {
    Map<String, Object> result=new LinkedHashMap<>();
    result.put("id",id); result.put("world",world); result.put("x",x); result.put("y",y); result.put("z",z);
    result.put("target",target(target[0],target[1],target[2])); result.put("launch-height",5); result.put("boost-speed",1.6);
    return result;
  }
  record Pad(String id, String world, int x, int y, int z, double targetX, double targetY, double targetZ, double height, double speed) {
    boolean matches(String w, double px, double py, double pz) {
      int bx = (int)Math.floor(px), bz = (int)Math.floor(pz);
      int feet = (int)Math.floor(py), below = (int)Math.floor(py - 0.01);
      return world.equals(w) && x == bx && z == bz && (y == feet || y == below);
    }
  }
  static boolean ready(long now, long last, long cooldownMillis) { return now - last >= cooldownMillis; }
  static double upwardVelocity(double height) { return Math.sqrt(2 * 0.08 * Math.max(0, height)); }
  static double[] boost(double dx, double dy, double dz, double speed) {
    double length = Math.sqrt(dx*dx + dy*dy + dz*dz);
    if (length < 0.000001) return new double[]{0, 0, 0};
    return new double[]{dx / length * speed, dy / length * speed, dz / length * speed};
  }
}
