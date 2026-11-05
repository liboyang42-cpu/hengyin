# [j2 rig seam] Stands in for api.weixin.qq.com: stable_token and img_sec_check only. Everything else -> 404.
import http.server, ssl, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('content-length') or 0); body = self.rfile.read(n)
        p = self.path.split('?')[0]
        if p == '/cgi-bin/stable_token': r = {"access_token": "rig-fake-token", "expires_in": 7200}
        elif p == '/wxa/img_sec_check': r = {"errcode": 87014, "errmsg": "risky"} if b'E2E-SEC-REJECT' in body else {"errcode": 0, "errmsg": "ok"}
        else: self.send_response(404); self.end_headers(); return
        b = json.dumps(r).encode(); self.send_response(200); self.send_header('content-type','application/json'); self.send_header('content-length',str(len(b))); self.end_headers(); self.wfile.write(b)
        sys.stderr.write('wxfake %s -> %s\n' % (p, r))
    do_GET = do_POST
s = http.server.ThreadingHTTPServer(('127.0.0.1', 443), H)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain('cert.pem', 'key.pem'); s.socket = ctx.wrap_socket(s.socket, server_side=True)
s.serve_forever()
