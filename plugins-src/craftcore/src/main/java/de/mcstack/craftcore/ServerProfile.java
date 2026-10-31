package de.mcstack.craftcore;

import java.util.Map;
import java.util.Locale;
import java.util.Set;

public final class ServerProfile {
  public static final Set<String> ECONOMY_COMMANDS = Set.of(
      "balance", "pay", "bank", "verkaufen", "market", "shop", "ah", "trade", "abholen", "testrun");

  private ServerProfile() {}

  public static boolean isPvp(String server) { return "pvp".equalsIgnoreCase(server); }

  public static boolean economyEnabled(String server) { return !isPvp(server); }

  public static boolean commandAllowed(boolean economyEnabled, String commandName) {
    return economyEnabled || (commandName != null && !ECONOMY_COMMANDS.contains(commandName.toLowerCase(Locale.ROOT)));
  }

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
