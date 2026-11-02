package de.mcstack.craftcore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Routing back to the lobby. Pure helpers only, so the wire format and the command names stay
 * unit-testable without a running proxy.
 */
final class SpawnRoute {
  /**
   * Channel Velocity registers for backends when {@code bungee-plugin-message-channel} is on.
   * It is the namespaced form of the legacy {@code BungeeCord} channel and the name the proxy
   * reads for {@code Connect}.
   */
  static final String CHANNEL = "bungeecord:main";
  static final String LOBBY = "lobby";
  /** Every spelling that has to lead back to the lobby, {@code /spawn} included. */
  static final List<String> LOBBY_COMMANDS = List.of("lobby", "l", "hub", "leave", "spawn");

  private SpawnRoute() {}

  static boolean isLobbyCommand(String name) {
    return name != null && LOBBY_COMMANDS.contains(name.toLowerCase(Locale.ROOT));
  }

  static boolean isLobby(String server) { return "lobby".equalsIgnoreCase(server); }
  static boolean isSurvival(String server) { return "survival".equalsIgnoreCase(server); }

  /** {@code Connect} payload in the format Velocity reads from a backend. */
  static byte[] connect(String server) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeUTF("Connect");
      out.writeUTF(server);
    }
    return bytes.toByteArray();
  }

  static byte[] connectMessage() throws IOException { return connect(LOBBY); }
}
