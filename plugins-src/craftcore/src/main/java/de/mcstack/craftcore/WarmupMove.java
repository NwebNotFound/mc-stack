package de.mcstack.craftcore;

final class WarmupMove {
  static boolean blockChanged(int fx,int fy,int fz,int tx,int ty,int tz) {
    return fx!=tx || fy!=ty || fz!=tz;
  }
}
