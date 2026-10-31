import os
import threading
import unittest
from http.client import HTTPConnection
from unittest.mock import patch
from http.server import ThreadingHTTPServer
import service

class Tests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"PVP_HOST_TOKEN": "test"})
        self.env.start()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), service.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.env.stop()
    def request(self, method, path, token=None):
        c = HTTPConnection("127.0.0.1", self.server.server_port)
        c.request(method, path, headers={"X-PvP-Token": token} if token else {})
        r = c.getresponse(); data = r.read(); c.close(); return r.status, data
    def test_auth_and_paths(self):
        with patch("service.subprocess.run") as run:
            for method, path in [("POST", "/start"), ("POST", "/stop"), ("GET", "/status")]:
                for token in (None, "wrong"):
                    with self.subTest(method=method, path=path, token=token):
                        self.assertEqual(401, self.request(method, path, token)[0])
            run.assert_not_called()
        self.assertEqual(404, self.request("POST", "/start?container=other", "test")[0])
        self.assertEqual(404, self.request("GET", "/start", "test")[0])
        self.assertEqual(404, self.request("GET", "/other", "test")[0])
        self.assertEqual(404, self.request("POST", "/other", "test")[0])
    def test_commands(self):
        with patch("service.subprocess.run") as run:
            run.return_value.stdout = "running\n"
            for method, path in [("POST", "/start"), ("POST", "/stop"), ("GET", "/status")]:
                self.assertEqual(200, self.request(method, path, "test")[0])
            self.assertEqual([c.args[0] for c in run.call_args_list], [["docker", "start", service.CONTAINER], ["docker", "stop", service.CONTAINER], ["docker", "inspect", "-f", "{{.State.Status}}", service.CONTAINER]])
    def test_mapping(self):
        for source, result in [("running", "online"), ("created", "sleeping"), ("exited", "sleeping"), ("restarting", "starting"), ("paused", "offline")]:
            self.assertEqual(result, service.status(source))

class VIPTests(Tests):
    WORLD = "12345678-1234-1234-1234-123456789abc"

    def setUp(self):
        import tempfile
        from pathlib import Path
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "config").mkdir()
        (self.root / "config" / "vipworlds.yml").write_text("""max-running: 9
slots:
  - vipworld-1
  - vipworld-2
  - vipworld-3
ram:
  total-mb: 90
  reserved-mb: 10
  survival-reserved-mb: 30
  fixed-services-mb:
    lobby: 20
    pvp: 10
  per-world-mb: 10
""", encoding="utf-8")
        (self.root / "data" / "vipworlds" / self.WORLD).mkdir(parents=True)
        super().setUp()
        self.vip_env = patch.dict(os.environ, {"VIP_HOST_TOKEN": "vip", "MC_STACK_DIR": str(self.root)})
        self.vip_env.start()

    def tearDown(self):
        self.vip_env.stop()
        super().tearDown()
        self.temp.cleanup()

    def vip(self, method, path, payload=None, token="vip"):
        import json
        c = HTTPConnection("127.0.0.1", self.server.server_port)
        c.request(method, path, body=json.dumps(payload) if payload is not None else None,
                  headers={"X-VIP-Token": token} if token else {})
        r = c.getresponse(); data = r.read(); c.close()
        return r.status, json.loads(data) if data else None

    @staticmethod
    def docker_result(state="exited", world=""):
        from subprocess import CompletedProcess
        return CompletedProcess([], 0, state + "|CRAFTCORE_VIP_WORLD_ID=" + world + "\n", "")

    def test_vip_auth_validation_and_stop(self):
        with patch("service.subprocess.run") as run:
            self.assertEqual(401, self.vip("GET", "/vip/status", token=None)[0])
            self.assertEqual(401, self.vip("GET", "/vip/status", token="test")[0])
            self.assertEqual(400, self.vip("POST", "/vip/start", {"worldId": "../bad"})[0])
            self.assertEqual(404, self.vip("POST", "/vip/start", {"worldId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"})[0])
            self.assertEqual(400, self.vip("POST", "/vip/stop", {"slot": "survival"})[0])
            run.assert_not_called()
            self.assertEqual((200, {"status": "stopping"}), self.vip("POST", "/vip/stop", {"slot": "vipworld-2"}))
            self.assertEqual(["docker", "stop", "mc-stack-vipworld-2-1"], run.call_args.args[0])

    def test_start_and_status(self):
        with patch("service.subprocess.run") as run:
            run.side_effect = [self.docker_result("running", "other"), self.docker_result(), self.docker_result(), self.docker_result()]
            self.assertEqual((202, {"status": "starting", "slot": "vipworld-2"}), self.vip("POST", "/vip/start", {"worldId": self.WORLD}))
            call = run.call_args
            self.assertEqual(["docker", "compose", "--profile", "vip", "up", "-d", "--force-recreate", "--no-deps", "vipworld-2"], call.args[0])
            self.assertEqual(self.WORLD, call.kwargs["env"]["VIPWORLD_2_ID"])
            self.assertEqual(str(self.root / "data" / "vipworlds" / self.WORLD), call.kwargs["env"]["VIPWORLD_2_DIR"])
            self.assertEqual(str(self.root), call.kwargs["cwd"])
            run.side_effect = [self.docker_result("running", self.WORLD), self.docker_result("restarting"), self.docker_result("created")]
            code, result = self.vip("GET", "/vip/status")
            self.assertEqual(200, code)
            self.assertEqual(["online", "starting", "sleeping"], [s["status"] for s in result["slots"]])
            self.assertEqual(self.WORLD, result["slots"][0]["worldId"])
            self.assertEqual(3, result["maxRunning"])
            self.assertFalse(result["budgetOk"])

    def test_existing_full_budget_and_third_profile(self):
        with patch("service.subprocess.run") as run:
            run.side_effect = [self.docker_result("running", self.WORLD), self.docker_result(), self.docker_result()]
            self.assertEqual((200, {"status": "running", "slot": "vipworld-1"}), self.vip("POST", "/vip/start", {"worldId": self.WORLD}))
            run.side_effect = [self.docker_result("running")] * 3
            self.assertEqual((409, {"status": "error", "code": "no_free_slot"}), self.vip("POST", "/vip/start", {"worldId": self.WORLD}))
            run.side_effect = [self.docker_result("running"), self.docker_result("running"), self.docker_result()]
            self.assertEqual((503, {"code": "ram_budget"}), self.vip("POST", "/vip/start", {"worldId": self.WORLD}))
            with patch("service.budget_ok", return_value=True):
                run.side_effect = [self.docker_result("running"), self.docker_result("running"), self.docker_result(), self.docker_result()]
                self.assertEqual(202, self.vip("POST", "/vip/start", {"worldId": self.WORLD})[0])
                self.assertIn("vip3", run.call_args.args[0])

    def test_budget_boundary_and_missing_container(self):
        cfg = service.read_config(self.root)
        self.assertTrue(service.budget_ok(cfg, 1))
        self.assertFalse(service.budget_ok(cfg, 2))
        from subprocess import CompletedProcess
        with patch("service.subprocess.run", return_value=CompletedProcess([], 1, "", "Error: No such object")):
            self.assertEqual("offline", service.inspect_slot("vipworld-1")["status"])
