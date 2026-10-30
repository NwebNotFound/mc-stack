package de.mcstack.craftcore;

final class LaunchPadLogic {
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
