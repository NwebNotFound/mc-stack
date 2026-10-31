package de.mcstack.craftcore;

import java.util.*;

final class NpcDefinitions {
  enum ActionType { SERVER, MENU, COMMAND, NONE }
  enum MenuAction { PVP, PVP_UNAVAILABLE, COMMAND, MENU }
  static MenuAction menuAction(String arg, boolean pvpAvailable) {
    if (arg.equalsIgnoreCase("pvp")) return pvpAvailable ? MenuAction.PVP : MenuAction.PVP_UNAVAILABLE;
    if (Set.of("bank", "shop", "ah", "stats").contains(arg.toLowerCase(Locale.ROOT))) return MenuAction.COMMAND;
    return MenuAction.MENU;
  }
  record Action(ActionType type, String arg) {}
  record NpcDef(String key, String displayName, String skin, String message, ActionType action, String actionArg) {
    NpcDef {
      if (displayName == null || displayName.isBlank() || displayName.length() > 64) throw new IllegalArgumentException("Name muss 1 bis 64 Zeichen lang sein");
      if (key == null || !key.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Ungueltiger Key");
      Objects.requireNonNull(skin); Objects.requireNonNull(message); Objects.requireNonNull(action); Objects.requireNonNull(actionArg);
      parseAction(action.name() + (actionArg.isEmpty() ? "" : " " + actionArg));
    }
  }
  static String stripColors(String s) { return s.replaceAll("(?i)[&?][0-9a-fk-orx]", ""); }
  static void validateDisplayName(Collection<NpcDef> defs, String key, String name) {
    if (name == null || name.length() > 64 || stripColors(name).isBlank()) throw new IllegalArgumentException("Name muss 1 bis 64 Zeichen lang sein");
    String stripped=stripColors(name);
    if (defs.stream().anyMatch(d->!d.key().equals(key) && stripColors(d.displayName()).equalsIgnoreCase(stripped))) throw new IllegalArgumentException("NPC-Name existiert bereits");
  }
  static String keyFromName(String name) {
    if (name == null || name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Ungueltiger Name");
    String key = stripColors(name).toLowerCase(Locale.ROOT).replaceAll("\\s+", "_").replaceAll("[^a-z0-9_-]", "");
    if (key.length() > 32) key = key.substring(0,32);
    if (key.isEmpty()) throw new IllegalArgumentException("Name ergibt keinen Key");
    return key;
  }
  static Action parseAction(String input) {
    if (input == null || input.isBlank()) throw new IllegalArgumentException("Aktion fehlt");
    String[] parts = input.trim().split("\\s+",2);
    ActionType type;
    try { type = ActionType.valueOf(parts[0].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Unbekannte Aktion"); }
    String arg = parts.length == 2 ? parts[1].trim() : "";
    if (type == ActionType.NONE ? !arg.isEmpty() : arg.isEmpty()) throw new IllegalArgumentException("Ungueltiges Aktionsargument");
    if ((type == ActionType.SERVER || type == ActionType.MENU) && !arg.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Ungueltiges Aktionsargument");
    if (type == ActionType.COMMAND && arg.startsWith("/")) throw new IllegalArgumentException("Befehl ohne Slash angeben");
    return new Action(type,arg);
  }
  static Map<String,Object> save(NpcDef d) {
    Map<String,Object> map = new LinkedHashMap<>();
    map.put("displayName",d.displayName()); map.put("skin",d.skin()); map.put("message",d.message()); map.put("action",d.action().name().toLowerCase(Locale.ROOT)); map.put("actionArg",d.actionArg()); return map;
  }
  static NpcDef load(String key, Map<String,?> map) {
    String name=Objects.toString(map.get("displayName"), ""), skin=Objects.toString(map.get("skin"), ""), message=Objects.toString(map.get("message"), ""), action=Objects.toString(map.get("action"), "none"), arg=Objects.toString(map.get("actionArg"), "");
    Action parsed=parseAction(action + (arg.isEmpty()?"":" "+arg)); return new NpcDef(key,name,skin,message,parsed.type(),parsed.arg());
  }
  static Optional<NpcDef> find(Collection<NpcDef> defs, String name) {
    String target=stripColors(name);
    return defs.stream().filter(d->d.key().equalsIgnoreCase(target) || stripColors(d.displayName()).equalsIgnoreCase(target)).findFirst();
  }
  static boolean inViewAndRange(double px,double py,double pz,double dx,double dy,double dz,double nx,double ny,double nz) { return inViewAndRange(px,py,pz,dx,dy,dz,nx,ny,nz,4.0,0.9); }
  static boolean inViewAndRange(double px,double py,double pz,double dx,double dy,double dz,double nx,double ny,double nz,double range,double cosMin) {
    double x=nx-px,y=ny-py,z=nz-pz, dist=x*x+y*y+z*z, dir=dx*dx+dy*dy+dz*dz;
    return range>=0 && dist>0 && dir>0 && dist<=range*range && (x*dx+y*dy+z*dz)/Math.sqrt(dist*dir)>=cosMin;
  }
}
