# [j2 rig seam] Minimal local stand-in for Aliyun OSS: stores PUT bodies, serves GET/HEAD. Path-style (endpoint is an IP).
import http.server, hashlib, os, sys, urllib.parse
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data')
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def _body(self):
        if (self.headers.get('transfer-encoding') or '').lower() == 'chunked':
            out = b''
            while True:
                ln = int(self.rfile.readline().strip().split(b';')[0], 16)
                if ln == 0: self.rfile.readline(); return out
                out += self.rfile.read(ln); self.rfile.readline()
        return self.rfile.read(int(self.headers.get('content-length') or 0))
    def _path(self):
        p = urllib.parse.urlparse(self.path).path.lstrip('/')
        if p.startswith('rig-offline/'): p = p[len('rig-offline/'):]
        return os.path.join(ROOT, p.replace('/', '__'))
    def do_PUT(self):
        b = self._body(); n = len(b)
        open(self._path(), 'wb').write(b)
        import json as _j; _j.dump({k: v for k, v in self.headers.items() if k.lower().startswith('x-oss-meta-') or k.lower()=='content-type'}, open(self._path()+'.meta','w'))
        et = '"%s"' % hashlib.md5(b).hexdigest().upper()
        self.send_response(200); self.send_header('ETag', et); self.send_header('x-oss-request-id', 'rig'); self.send_header('Content-Length', '0'); self.end_headers()
        sys.stderr.write('PUT %s %d\n' % (self.path, n))
    def do_GET(self, head=False):
        f = self._path()
        if not os.path.exists(f):
            self.send_response(404); self.send_header('Content-Length', '0'); self.end_headers(); return
        b = open(f, 'rb').read()
        import json as _j; meta = _j.load(open(f+'.meta')) if os.path.exists(f+'.meta') else {}
        self.send_response(200)
        for mk, mv in meta.items(): self.send_header(mk, mv)
        if not any(k.lower()=='content-type' for k in meta): self.send_header('Content-Type', 'image/jpeg')
        self.send_header('Last-Modified', 'Thu, 05 Nov 2026 03:00:00 GMT'); self.send_header('Content-Length', str(len(b))); self.send_header('ETag', '"%s"' % hashlib.md5(b).hexdigest().upper()); self.send_header('x-oss-request-id', 'rig'); self.end_headers()
        if not head: self.wfile.write(b)
    def do_HEAD(self): self.do_GET(head=True)
    def log_message(self, *a): pass
import ssl
s = http.server.ThreadingHTTPServer((os.environ.get('OSS_BIND','127.0.0.1'), int(os.environ.get('OSS_PORT','9443'))), H)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(os.path.join(os.path.dirname(ROOT),'cert.pem'), os.path.join(os.path.dirname(ROOT),'key.pem'))
s.socket = ctx.wrap_socket(s.socket, server_side=True)
s.serve_forever()
