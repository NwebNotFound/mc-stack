package de.mcstack.craftcore.pvp;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minimal Minecraft server-list ping: true only once the server actually answers (not just container running). */
public final class PvpPing {
    private final String host;
    private final int port;
    public PvpPing() { this(System.getenv().getOrDefault("PVP_PING_HOST", "pvp"), Integer.parseInt(System.getenv().getOrDefault("PVP_PING_PORT", "25565"))); }
    PvpPing(String host, int port) { this.host = host; this.port = port; }

    public boolean ping() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(handshake(host, port)); out.write(new byte[] {1, 0}); out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            readVarInt(in); return readVarInt(in) == 0; // packet length, packet id 0 = status response
        } catch (IOException | RuntimeException e) { return false; }
    }
    static byte[] handshake(String host, int port) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        writeVarInt(d, 0); writeVarInt(d, -1);
        byte[] h = host.getBytes(StandardCharsets.UTF_8); writeVarInt(d, h.length); d.write(h);
        d.writeShort(port); writeVarInt(d, 1);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(packet);
        writeVarInt(p, body.size()); p.write(body.toByteArray());
        return packet.toByteArray();
    }
    static void writeVarInt(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) { out.writeByte((value & 0x7F) | 0x80); value >>>= 7; }
        out.writeByte(value);
    }
    static int readVarInt(DataInput in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) { byte b = in.readByte(); value |= (b & 0x7F) << (7 * i); if ((b & 0x80) == 0) return value; }
        throw new IOException("VarInt zu lang");
    }
    static String progress(long elapsedSeconds, long timeoutSeconds) {
        return "PvP-Server startet ... " + elapsedSeconds + "s / " + timeoutSeconds + "s";
    }
}
