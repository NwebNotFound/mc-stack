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
