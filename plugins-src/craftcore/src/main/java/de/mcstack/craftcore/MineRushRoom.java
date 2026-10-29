package de.mcstack.craftcore;

import java.util.*;

/** Geometry and validation for a saved room. Coordinates are relative to its minimum corner. */
final class MineRushRoom {
  enum Type { KAMPF, PUZZLE, BOSS, START, FLUR, LOOT }
  enum MarkerType { ENTRANCE, EXIT, SPAWN, BOSS_SPAWN, LOOT, PUZZLE }
  enum Facing {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
    final int dx, dz;
    Facing(int dx, int dz) { this.dx = dx; this.dz = dz; }
    Facing opposite() { return values()[(ordinal() + 2) % 4]; }
    Facing rotate(int turns) { return values()[Math.floorMod(ordinal() + turns, 4)]; }
  }
  record Point(int x, int y, int z) {
    Point plus(Point other) { return new Point(x + other.x, y + other.y, z + other.z); }
    Point minus(Point other) { return new Point(x - other.x, y - other.y, z - other.z); }
  }
  record Marker(MarkerType type, Point position, Facing facing) {
    Marker { Objects.requireNonNull(type); Objects.requireNonNull(position); }
  }
  record Definition(String name, Type type, int width, int height, int depth, List<Marker> markers) {
    Definition { Objects.requireNonNull(name); Objects.requireNonNull(type); markers = List.copyOf(markers); }
  }

  static List<String> validate(Definition room) {
    List<String> errors = new ArrayList<>();
    if (!room.name().matches("[a-zA-Z0-9_-]{1,32}")) errors.add("Ungueltiger Raumname");
    if (room.width() < 1 || room.height() < 1 || room.depth() < 1 ||
        room.width() > 64 || room.height() > 64 || room.depth() > 64 ||
        (long) room.width() * room.height() * room.depth() > 65536) errors.add("Ungueltige Raumgroesse");
    int entrances = 0, exits = 0;
    Set<Point> occupied = new HashSet<>();
    for (Marker marker : room.markers()) {
      Point p = marker.position();
      if (p.x() < 0 || p.x() >= room.width() || p.y() < 0 || p.y() >= room.height() || p.z() < 0 || p.z() >= room.depth()) errors.add("Marker ausserhalb des Raums");
      if (!occupied.add(p)) errors.add("Mehrere Marker an derselben Position");
      if (marker.type() == MarkerType.ENTRANCE || marker.type() == MarkerType.EXIT) {
        if (marker.type() == MarkerType.ENTRANCE) entrances++; else exits++;
        Facing f = marker.facing();
        if (f == null || !onBoundary(room, p, f)) errors.add("Anschluss muss nach aussen an einer Raumkante liegen");
      } else if (marker.facing() != null) errors.add("Nur Anschluesse haben eine Richtung");
    }
    if (entrances != 1) errors.add("Genau ein Eingang erforderlich");
    if (exits != 1) errors.add("Genau ein Ausgang erforderlich");
    if (room.type() == Type.PUZZLE && room.markers().stream().noneMatch(m -> m.type() == MarkerType.PUZZLE)) errors.add("Puzzle-Marker fehlt");
    if (room.type() == Type.BOSS && room.markers().stream().noneMatch(m -> m.type() == MarkerType.BOSS_SPAWN)) errors.add("Boss-Spawn fehlt");
    return List.copyOf(errors);
  }
  private static boolean onBoundary(Definition r, Point p, Facing f) {
    return switch (f) { case NORTH -> p.z() == 0; case SOUTH -> p.z() == r.depth()-1; case WEST -> p.x() == 0; case EAST -> p.x() == r.width()-1; };
  }
  /** Rotate around the minimum corner and re-normalize to nonnegative coordinates. */
  static Point rotate(Point p, int width, int depth, int turns) {
    return switch (Math.floorMod(turns, 4)) {
      case 0 -> p; case 1 -> new Point(depth - 1 - p.z(), p.y(), p.x());
      case 2 -> new Point(width - 1 - p.x(), p.y(), depth - 1 - p.z());
      default -> new Point(p.z(), p.y(), width - 1 - p.x());
    };
  }
  /** Place the next room with its entrance immediately outside the previous exit. */
  static Placement connect(Point exitWorld, Facing exitFacing, Definition next) {
    List<String> errors = validate(next);
    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(", ", errors));
    Marker entrance = next.markers().stream().filter(m -> m.type() == MarkerType.ENTRANCE).findFirst().orElseThrow();
    int turns = Math.floorMod(exitFacing.opposite().ordinal() - entrance.facing().ordinal(), 4);
    Point target = exitWorld.plus(new Point(exitFacing.dx, 0, exitFacing.dz));
    return new Placement(target.minus(rotate(entrance.position(), next.width(), next.depth(), turns)), turns);
  }
  record Placement(Point origin, int quarterTurns) {}
  private MineRushRoom() {}
}
