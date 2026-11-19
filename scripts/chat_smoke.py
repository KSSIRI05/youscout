"""Exercise Chat through the Gateway, REST and WebSocket, and its calls to User; nothing is written."""
import base64
import json
import os
import socket
import struct
import uuid
from catalogue_smoke import request

HOST, PORT = "localhost", 8080


class Socket:
    """Client WebSocket minimal (RFC 6455, trames texte non fragmentées) pour éviter toute dépendance."""

    def __init__(self, path="/v1/chat/ws"):
        self.sock = socket.create_connection((HOST, PORT), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(1024)
            if not chunk:
                raise AssertionError("Gateway closed the WebSocket handshake")
            head += chunk
        status = head.split(b"\r\n", 1)[0]
        assert b" 101 " in status, f"WebSocket handshake refused through the Gateway ({status.decode(errors='replace')})"
        self.buffer = head.split(b"\r\n\r\n", 1)[1]

    def send(self, frame):
        payload, mask = json.dumps(frame).encode(), os.urandom(4)
        header = bytes([0x81]) + (bytes([0x80 | len(payload)]) if len(payload) < 126 else bytes([0x80 | 126]) + struct.pack("!H", len(payload)))
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def _read(self, n):
        while len(self.buffer) < n:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise AssertionError("WebSocket closed unexpectedly")
            self.buffer += chunk
        data, self.buffer = self.buffer[:n], self.buffer[n:]
        return data

    def receive(self):
        """Renvoie ("text", objet JSON) ou ("close", code)."""
        first, second = self._read(2)
        length = second & 0x7F
        if length == 126:
            length = struct.unpack("!H", self._read(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self._read(8))[0]
        payload = self._read(length)
        opcode = first & 0x0F
        if opcode == 0x8:
            return "close", struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else None
        return "text", json.loads(payload)

    def close(self):
        self.sock.close()


def authenticate(ws, token):
    """Comme l'application : renvoie la trame auth si « ready » n'arrive pas en 3 s (trame perdue à l'ouverture)."""
    for attempt in range(3):
        ws.send({"type": "auth", "token": token})
        ws.sock.settimeout(3)
        try:
            return ws.receive()
        except TimeoutError:
            continue
        finally:
            ws.sock.settimeout(10)
    raise AssertionError("WebSocket authentication got no answer")


def exercise(player_token, player_subject):
    stranger = "missing-" + uuid.uuid4().hex
    # REST : lecture vide, destinataire inconnu refusé par User, message à soi-même refusé avant tout appel.
    status, conversations = request("GET", "/v1/conversations", player_token)
    assert status == 200 and isinstance(conversations, list), f"Chat conversation list failed (HTTP {status})"
    status, history = request("GET", f"/v1/conversations/{stranger}/messages", player_token)
    assert status == 200 and history["messages"] == [] and history["lastSeq"] == 0, f"Chat history read failed (HTTP {status})"
    status, _ = request("POST", f"/v1/conversations/{stranger}/messages", player_token, {"clientMessageId": "smoke-1", "body": "Smoke"})
    assert status == 404, f"Message to an unknown profile was not rejected by User (HTTP {status})"
    status, _ = request("POST", f"/v1/conversations/{player_subject}/messages", player_token, {"clientMessageId": "smoke-2", "body": "Smoke"})
    assert status == 400, f"Self-message accepted (HTTP {status})"

    # WebSocket : sans authentification la connexion est fermée en 4401, dès la trame ou au plus tard après 10 s.
    # La Gateway peut perdre une trame envoyée juste après l'ouverture : seule la fermeture de Chat compte ici.
    ws = Socket()
    try:
        ws.sock.settimeout(25)
        ws.send({"type": "ping"})
        kind, value = ws.receive()
        ws.sock.settimeout(10)
        assert (kind, value) == ("close", 4401), f"Unauthenticated WebSocket was not closed with 4401 ({kind} {value})"
    finally:
        ws.close()
    # Authentifiée : ping, puis envoi refusé par User, renvoyé comme erreur sur la même connexion.
    ws = Socket()
    try:
        kind, ready = authenticate(ws, player_token)
        assert kind == "text" and ready["type"] == "ready" and ready["subject"] == player_subject, "WebSocket authentication failed"
        ws.send({"type": "ping"})
        assert ws.receive() == ("text", {"type": "pong"}), "WebSocket ping failed"
        ws.send({"type": "send", "to": stranger, "clientMessageId": "smoke-3", "body": "Smoke"})
        kind, error = ws.receive()
        assert kind == "text" and error["type"] == "error" and error["status"] == 404 and error["clientMessageId"] == "smoke-3", \
            f"WebSocket send to an unknown profile was not refused ({error})"
    finally:
        ws.close()
    print("PASS: Chat REST and WebSocket through the Gateway, token required, recipient checked with User.", flush=True)
