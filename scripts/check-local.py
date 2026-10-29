"""Minecraft Java status handshake at the loopback-only Velocity listener."""
import json
import socket
import struct
import sys


def varint(value):
    result = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        result.append(part | (0x80 if value else 0))
        if not value:
            return bytes(result)


def read_exact(stream, size):
    result = bytearray()
    while len(result) < size:
        chunk = stream.recv(size - len(result))
        if not chunk:
            raise ConnectionError("Verbindung vor der Statusantwort geschlossen")
        result.extend(chunk)
    return bytes(result)


def read_varint(stream):
    result = 0
    for offset in range(5):
        byte = read_exact(stream, 1)[0]
        result |= (byte & 0x7F) << (7 * offset)
        if not byte & 0x80:
            return result
    raise ValueError("Ungueltiger VarInt")


def main():
    host = b"127.0.0.1"
    # Status requests do not authenticate; protocol -1 requests version discovery.
    handshake = (b"\x00" + varint(0xFFFFFFFF) + varint(len(host)) + host
                 + struct.pack(">H", 25565) + b"\x01")
    with socket.create_connection(("127.0.0.1", 25565), timeout=10) as stream:
        stream.sendall(varint(len(handshake)) + handshake + b"\x01\x00")
        size = read_varint(stream)
        if not 0 < size <= 2_000_000:
            raise ValueError("Unerwartete Paketgroesse")
        # Parse the response from a bounded in-memory stream.
        import io
        packet = io.BytesIO(read_exact(stream, size))

        def number():
            value = 0
            for offset in range(5):
                byte = packet.read(1)
                if not byte:
                    raise ValueError("Unvollstaendige Statusantwort")
                value |= (byte[0] & 0x7F) << (7 * offset)
                if not byte[0] & 0x80:
                    return value
            raise ValueError("Ungueltiger Status-VarInt")

        if number() != 0:
            raise ValueError("Unerwartete Paket-ID")
        length = number()
        payload = packet.read(length)
        if len(payload) != length:
            raise ValueError("Unvollstaendige JSON-Antwort")
        status = json.loads(payload)
        print("OK: Minecraft-Statusantwort von 127.0.0.1:25565")
        print("Version:", status["version"]["name"])
        print("Spieler:", status["players"]["online"], "/", status["players"]["max"])
        print("Ein authentifizierter Login mit Whitelist-Spieler bleibt separat zu pruefen.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        print("FEHLER:", error, file=sys.stderr)
        sys.exit(1)
