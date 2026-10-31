package de.mcstack.craftcore;

import java.util.Map;

public final class ServerProfile {
  private ServerProfile() {}

  public static boolean isPvp(String server) { return "pvp".equalsIgnoreCase(server); }

  public static boolean economyEnabled(String server) { return !isPvp(server); }

  public static String pvpStartupError(String password, boolean driverLoaded) {
    if (password == null || password.isBlank()) return "CRAFTCORE_DB_PASSWORD fehlt oder ist leer";
    if (!driverLoaded) return "MariaDB-Treiber org.mariadb.jdbc.Driver fehlt";
    return null;
  }

  public static String dbUser(Map<String, String> env) {
    String user = env.get("CRAFTCORE_DB_USER");
    return user == null || user.isBlank() ? "craftcore" : user.trim();
  }
}
