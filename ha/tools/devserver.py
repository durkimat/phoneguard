#!/usr/bin/env python3
"""Stand-in for Home Assistant while developing: serves the same Hub over plain HTTP.
POST /hook is the phone's endpoint. GET /admin/<action>?... (from this machine only) plays the parent.
State is kept in a JSON file so a restart does not lose the pairing."""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import purepkg  # noqa: E402

purepkg.load()
from phoneguard.hub import Hub  # noqa: E402

STATE = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/phoneguard-dev.json")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8099
HOST_URL = sys.argv[3] if len(sys.argv) > 3 else f"http://127.0.0.1:{PORT}/hook"
LOCK = threading.Lock()
data = json.loads(STATE.read_text()) if STATE.exists() else {}
hub = Hub(data)
hub.ensure_child("her", "Her")


def save():
    STATE.write_text(json.dumps(hub.data))


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a):
        sys.stderr.write("%s\n" % (fmt % a))

    def _send(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        if urlparse(self.path).path != "/hook":
            return self._send({"error": "no"}, 404)
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            env = json.loads(raw)
        except ValueError:
            return self._send({"error": "bad"}, 400)
        with LOCK:
            reply = hub.handle(env); save()
        self._send(reply)

    def do_GET(self):
        u = urlparse(self.path)
        if self.client_address[0] != "127.0.0.1" or not u.path.startswith("/admin/"):
            return self._send({"error": "no"}, 404)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        act, cid = u.path.split("/")[2], q.get("child", "her")
        with LOCK:
            try:
                if act == "pair": out = hub.create_pairing(cid, HOST_URL)
                elif act == "confirm": out = {"ok": hub.confirm_pairing(cid, q["sas"])}
                elif act == "boost": out = hub.boost(cid, q.get("mode", "day"), int(q.get("minutes", 15)))
                elif act == "cancel_boost": hub.cancel_boost(cid); out = {"ok": True}
                elif act == "set_app": hub.set_app_mode(cid, q["pkg"], q["mode"], q["allowed"] == "1"); out = {"ok": True}
                elif act == "approve": hub.approve_app(cid, q["pkg"], q["modes"].split(",")); out = {"ok": True}
                elif act == "holiday": hub.set_holiday(cid, q.get("from"), q.get("until")); out = {"ok": True}
                elif act == "release": out = {"ok": hub.release(cid)}
                elif act == "unknown":  # test only: pretend the parent has never seen this package
                    ch = hub.data["children"][cid]
                    ch["known"] = [k for k in (ch.get("known") or []) if k != q["pkg"]]; out = {"ok": True}
                elif act == "forget": hub.forget(cid); out = {"ok": True}
                elif act == "view": out = hub.view(cid)
                else: out = {"error": "unknown"}
            except Exception as e:  # dev tool: report, do not crash
                out = {"error": f"{type(e).__name__}: {e}"}
            save()
        self._send(out)


if __name__ == "__main__":
    print(f"dev server on :{PORT}, state {STATE}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
