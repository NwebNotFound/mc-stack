"""Small fixed-purpose HTTP bridge for the PvP and VIP containers."""
import hmac
import json
import os
import re
import subprocess
import threading
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONTAINER = "mc-stack-pvp-1"
VIP_LOCK = threading.Lock()
# Every docker call is bounded (issue #147): a hung `docker compose up` or `docker inspect` (e.g. while Docker Desktop
# restarts) must not hold VIP_LOCK forever. A timeout is a SubprocessError and becomes HTTP 503.
INSPECT_TIMEOUT = 30
LOGS_TIMEOUT = 30
# `docker stop` waits for the container's stop grace period (2 min for the Paper services in compose.yaml).
STOP_TIMEOUT = 150
START_TIMEOUT = 60
COMPOSE_UP_TIMEOUT = 180
# A request waits at most this long for another VIP request to finish, then gets 503 instead of queueing forever.
VIP_LOCK_TIMEOUT = COMPOSE_UP_TIMEOUT + 30
TIMEOUTS = {"inspect": INSPECT_TIMEOUT, "start": START_TIMEOUT, "stop": STOP_TIMEOUT}
UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\Z")
SLOT_RE = re.compile(r"vipworld-[1-3]\Z")


def docker(action):
    args = ["docker", action]
    if action == "inspect":
        args += ["-f", "{{.State.Status}}"]
    args.append(CONTAINER)
    return subprocess.run(args, capture_output=True, text=True, check=True, timeout=TIMEOUTS[action]).stdout.strip()


def status(value):
    return {"running": "online", "created": "sleeping", "exited": "sleeping", "restarting": "starting"}.get(value, "offline")


def repo_root():
    return Path(os.environ.get("MC_STACK_DIR") or Path(__file__).resolve().parents[2]).resolve()


def read_config(root):
    """Read the small, known subset of the VIP YAML configuration; fail closed."""
    cfg = {"slots": [], "ram": {"fixed-services-mb": {}}}
    section = None
    subsection = None
    for raw in (root / "config" / "vipworlds.yml").read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        value = line.strip()
        if indent == 0:
            subsection = None
            key, sep, val = value.partition(":")
            if not sep:
                raise ValueError("invalid configuration")
            section = key
            if key == "max-running":
                cfg[key] = int(val.strip())
        elif section == "slots" and indent == 2 and value.startswith("- "):
            slot = value[2:].strip()
            if not SLOT_RE.fullmatch(slot) or slot in cfg["slots"]:
                raise ValueError("invalid slot")
            cfg["slots"].append(slot)
        elif section == "ram":
            key, sep, val = value.partition(":")
            if not sep:
                raise ValueError("invalid RAM configuration")
            if indent == 2:
                subsection = key
                if key in ("total-mb", "reserved-mb", "survival-reserved-mb", "per-world-mb"):
                    cfg["ram"][key] = int(val.strip())
            elif indent == 4 and subsection == "fixed-services-mb":
                cfg["ram"]["fixed-services-mb"][key] = int(val.strip())
    cfg["max-running"] = min(cfg["max-running"], 3)
    ram = cfg["ram"]
    values = [cfg["max-running"], *(ram[k] for k in ("total-mb", "reserved-mb", "survival-reserved-mb", "per-world-mb")), *ram["fixed-services-mb"].values()]
    if not cfg["slots"] or not ram["fixed-services-mb"] or any(v < 0 for v in values):
        raise ValueError("invalid VIP configuration")
    return cfg


def budget_ok(cfg, running):
    ram = cfg["ram"]
    return ram["survival-reserved-mb"] + sum(ram["fixed-services-mb"].values()) + (running + 1) * ram["per-world-mb"] <= ram["total-mb"] - ram["reserved-mb"]


def inspect_slot(slot):
    container = "mc-stack-" + slot + "-1"
    result = subprocess.run(["docker", "inspect", "-f", '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{range .Config.Env}}{{println .}}{{end}}', container], capture_output=True, text=True, timeout=INSPECT_TIMEOUT)
    if result.returncode:
        if "no such" in (result.stderr or "").lower():
            return {"slot": slot, "status": "offline", "worldId": None}
        raise subprocess.CalledProcessError(result.returncode, result.args, result.stdout, result.stderr)
    state, _, remainder = result.stdout.partition("|")
    health, _, env = remainder.partition("|")
    world_id = next((line[len("CRAFTCORE_VIP_WORLD_ID="):] for line in env.splitlines() if line.startswith("CRAFTCORE_VIP_WORLD_ID=")), None)
    slot_status = status(state.strip())
    if state.strip() == "running":
        if health.strip():
            ready = health.strip() == "healthy"
        else:
            logs = subprocess.run(["docker", "logs", "--tail", "200", container], capture_output=True, text=True, check=True, timeout=LOGS_TIMEOUT)
            output = (logs.stdout or "") + (logs.stderr or "")
            ready = any("Done (" in line and "! For help" in line for line in output.splitlines())
        slot_status = "online" if ready else "starting"
    return {"slot": slot, "status": slot_status, "worldId": world_id or None}


class Handler(BaseHTTPRequestHandler):
    # Socket timeout for reading a request: a client that never sends its body cannot pin a thread.
    timeout = 30

    def json_response(self, code, value, compact=False):
        body = json.dumps(value, separators=(",", ":") if compact else None).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def handle_request(self):
        pvp = (self.command, self.path) in {("POST", "/start"), ("POST", "/stop"), ("GET", "/status")}
        vip = (self.command, self.path) in {("POST", "/vip/start"), ("POST", "/vip/stop"), ("GET", "/vip/status")}
        if not (pvp or vip):
            self.send_response(404); self.end_headers(); return
        token = os.environ.get("PVP_HOST_TOKEN" if pvp else "VIP_HOST_TOKEN", "")
        supplied = self.headers.get("X-PvP-Token" if pvp else "X-VIP-Token", "")
        if not token or not hmac.compare_digest(token.encode(), supplied.encode()):
            self.send_response(401); self.end_headers(); return
        if pvp:
            try:
                result = status(docker("inspect")) if self.path == "/status" else (docker(self.path[1:]) and "ok") or "ok"
                self.json_response(200, {"status": result})
            except (OSError, subprocess.SubprocessError):
                self.json_response(503, {"status": "offline"}, compact=True)
            return
        if not VIP_LOCK.acquire(timeout=VIP_LOCK_TIMEOUT):
            self.json_response(503, {"status": "error", "code": "busy"}); return
        try:
            self.handle_vip()
        except subprocess.TimeoutExpired:
            self.json_response(503, {"status": "error", "code": "docker_timeout"})
        except (OSError, subprocess.SubprocessError, ValueError, KeyError):
            self.json_response(503, {"status": "error", "code": "service_unavailable"})
        finally:
            VIP_LOCK.release()

    def handle_vip(self):
        root = repo_root()
        cfg = read_config(root)
        if self.command == "POST":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 65536:
                    raise ValueError()
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict):
                    raise ValueError()
            except (ValueError, UnicodeDecodeError):
                self.json_response(400, {"status": "error", "code": "invalid_request"}); return
            if self.path == "/vip/stop":
                slot = payload.get("slot")
                if not isinstance(slot, str) or slot not in cfg["slots"]:
                    self.json_response(400, {"status": "error", "code": "invalid_slot"}); return
                subprocess.run(["docker", "stop", "mc-stack-" + slot + "-1"], capture_output=True, text=True, check=True, timeout=STOP_TIMEOUT)
                self.json_response(200, {"status": "stopping"}); return
            world_id = payload.get("worldId")
            if not isinstance(world_id, str) or not UUID_RE.fullmatch(world_id):
                self.json_response(400, {"status": "error", "code": "invalid_world_id"}); return
            base = root / "data" / "vipworlds"
            if not base.is_dir():
                self.json_response(500, {"status": "error", "code": "base_missing"}); return
            base = base.resolve()
            world_dir = base / world_id
            if world_dir.is_symlink() or world_dir.resolve().parent != base:
                self.json_response(400, {"status": "error", "code": "invalid_world_path"}); return
            if world_dir.exists() and not world_dir.is_dir():
                self.json_response(400, {"status": "error", "code": "invalid_world_path"}); return
            try:
                os.makedirs(world_dir, exist_ok=True)
            except FileExistsError:
                self.json_response(400, {"status": "error", "code": "invalid_world_path"}); return
            if world_dir.is_symlink() or not world_dir.is_dir() or world_dir.resolve().parent != base:
                self.json_response(400, {"status": "error", "code": "invalid_world_path"}); return
        slots = [inspect_slot(slot) for slot in cfg["slots"]]
        active = [slot for slot in slots if slot["status"] in ("online", "starting")]
        if self.path == "/vip/status":
            self.json_response(200, {"slots": slots, "maxRunning": cfg["max-running"], "budgetOk": budget_ok(cfg, len(active))}); return
        for slot in active:
            if slot["worldId"] and slot["worldId"].lower() == world_id.lower():
                ready = slot["status"] == "online"
                self.json_response(200 if ready else 202, {"status": "running" if ready else "starting", "slot": slot["slot"]}); return
        free = next((slot["slot"] for slot in slots if slot["status"] not in ("online", "starting")), None)
        if len(active) >= cfg["max-running"] or free is None:
            self.json_response(409, {"status": "error", "code": "no_free_slot"}); return
        if not budget_ok(cfg, len(active)):
            self.json_response(503, {"code": "ram_budget"}); return
        env = os.environ.copy()
        number = free.rsplit("-", 1)[1]
        env["VIPWORLD_" + number + "_DIR"] = str(world_dir.resolve())
        env["VIPWORLD_" + number + "_ID"] = world_id
        args = ["docker", "compose", "--profile", "vip"]
        if free == "vipworld-3":
            args += ["--profile", "vip3"]
        args += ["up", "-d", "--force-recreate", "--no-deps", free]
        subprocess.run(args, cwd=str(root), env=env, capture_output=True, text=True, check=True, timeout=COMPOSE_UP_TIMEOUT)
        self.json_response(202, {"status": "starting", "slot": free})

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
    if not (os.environ.get("PVP_HOST_TOKEN") or os.environ.get("VIP_HOST_TOKEN")):
        raise SystemExit("PVP_HOST_TOKEN oder VIP_HOST_TOKEN fehlt")
    ThreadingHTTPServer(((os.environ.get("PVP_HOST_BIND") or "127.0.0.1"), int(os.environ.get("PVP_HOST_PORT") or "25599")), Handler).serve_forever()
