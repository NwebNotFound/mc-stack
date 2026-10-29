package de.mcstack.craftcore;

import java.util.UUID;

final class TeamTeleportRules {
  static boolean active(long expires,long now){return expires>now;}
  static boolean recipient(UUID expected,UUID actual,long expires,long now){return expected.equals(actual) && active(expires,now);}
  static boolean inCombat(long lastDamage,long now){return lastDamage>0 && now-lastDamage<10_000;}
  private TeamTeleportRules() {}
}
