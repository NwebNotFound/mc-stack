package de.mcstack.status;

import java.io.*;

record SwitchRequest(long revision, String target) {
  static SwitchRequest decode(byte[] payload) throws IOException {
    if (payload.length > 80) throw new IOException("oversize");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
      long revision = in.readLong();
      String target = in.readUTF();
      if (revision < 1 || !target.matches("[a-zA-Z0-9_-]{1,32}") || in.read() != -1) throw new IOException("invalid");
      return new SwitchRequest(revision, target);
    }
  }
}
