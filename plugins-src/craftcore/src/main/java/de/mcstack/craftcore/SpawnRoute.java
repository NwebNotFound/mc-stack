package de.mcstack.craftcore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class SpawnRoute {
  static boolean isLobby(String server) { return "lobby".equalsIgnoreCase(server); }
  static boolean isSurvival(String server) { return "survival".equalsIgnoreCase(server); }
  static byte[] connectMessage() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeUTF("Connect");
      out.writeUTF("lobby");
    }
    return bytes.toByteArray();
  }
}
