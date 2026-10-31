package de.mcstack.craftcore;

import java.util.Map;

public final class ServerProfile {
  private ServerProfile() {}

  public static boolean isPvp(String server) { return "pvp".equalsIgnoreCase(server); }

  public static boolean economyEnabled(String server) { return !isPvp(server); }

  public static String dbUser(Map<String, String> env) {
    String user = env.get("CRAFTCORE_DB_USER");
    return user == null || user.isBlank() ? "craftcore" : user.trim();
  }
}
