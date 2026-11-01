package de.mcstack.craftcore.pvp;

import java.io.*;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvpPingTest {
    @Test void varIntRoundTrips() throws IOException {
        for (int v : new int[] {0, 1, 127, 128, 25565, -1}) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            PvpPing.writeVarInt(new DataOutputStream(b), v);
            assertEquals(v, PvpPing.readVarInt(new DataInputStream(new ByteArrayInputStream(b.toByteArray()))));
        }
    }
    @Test void handshakeIsLengthPrefixedStatusHandshake() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(PvpPing.handshake("pvp", 25565)));
        int length = PvpPing.readVarInt(in);
        assertEquals(length, in.available());
        assertEquals(0, PvpPing.readVarInt(in));
        assertEquals(-1, PvpPing.readVarInt(in));
        assertEquals(3, PvpPing.readVarInt(in));
        byte[] host = new byte[3]; in.readFully(host); assertEquals("pvp", new String(host));
        assertEquals(25565, in.readUnsignedShort());
        assertEquals(1, PvpPing.readVarInt(in));
    }
    @Test void progressShowsElapsedAndTimeout() {
        assertEquals("PvP-Server startet ... 12s / 180s", PvpPing.progress(12, 180));
    }
    @Test void closedPortIsNotReady() throws IOException {
        int port; try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        assertFalse(new PvpPing("127.0.0.1", port).ping());
    }
}
