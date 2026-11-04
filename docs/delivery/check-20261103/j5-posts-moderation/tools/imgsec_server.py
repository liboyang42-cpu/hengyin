"""Rig-only local stand-in for WeChat img_sec_check. Mode from /tmp/rig-imgsec-mode: pass | risky | busy."""
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("content-length") or 0); self.rfile.read(n) if n else None
        try: m = open("/tmp/rig-imgsec-mode").read().strip()
        except Exception: m = "pass"
        body = {"pass": '{"errcode":0,"errmsg":"ok"}', "risky": '{"errcode":87014,"errmsg":"risky content"}',
                "busy": '{"errcode":-1,"errmsg":"system busy"}'}.get(m, '{"errcode":0}')
        self.send_response(200); self.send_header("content-type", "application/json"); self.end_headers(); self.wfile.write(body.encode())
    def log_message(self, *a): pass
HTTPServer(("127.0.0.1", 8095), H).serve_forever()
