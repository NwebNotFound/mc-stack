"""Small fixed-purpose HTTP bridge for the PvP container."""
import hmac
import json
import os
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONTAINER = "mc-stack-pvp-1"

def docker(action):
    args = ["docker", action]
    if action == "inspect":
        args += ["-f", "{{.State.Status}}"]
    args.append(CONTAINER)
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()

def status(value):
    return {"running": "online", "created": "sleeping", "exited": "sleeping", "restarting": "starting"}.get(value, "offline")

class Handler(BaseHTTPRequestHandler):
    def handle_request(self):
        if (self.command, self.path) not in {("POST", "/start"), ("POST", "/stop"), ("GET", "/status")}:
            self.send_response(404); self.end_headers(); return
        token = os.environ.get("PVP_HOST_TOKEN", "")
        supplied = self.headers.get("X-PvP-Token", "")
        if not token or not hmac.compare_digest(token.encode(), supplied.encode()):
            self.send_response(401); self.end_headers(); return
        try:
            result = status(docker("inspect")) if self.path == "/status" else (docker(self.path[1:]) and "ok") or "ok"
            body = json.dumps({"status": result}).encode()
            self.send_response(200)
        except (OSError, subprocess.SubprocessError):
            body = b'{"status":"offline"}'
            self.send_response(503)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def send_error(self, code, message=None, explain=None):
        if code == 501:
            self.send_response(404); self.end_headers(); return
        super().send_error(code, message, explain)

    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request
    do_DELETE = handle_request
    do_PATCH = handle_request

if __name__ == "__main__":
    if not os.environ.get("PVP_HOST_TOKEN"):
        raise SystemExit("PVP_HOST_TOKEN fehlt")
    ThreadingHTTPServer(((os.environ.get("PVP_HOST_BIND") or "127.0.0.1"), int(os.environ.get("PVP_HOST_PORT") or "25599")), Handler).serve_forever()
