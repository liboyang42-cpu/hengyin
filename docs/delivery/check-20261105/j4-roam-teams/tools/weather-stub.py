# J4-1105 HARNESS: stands in for apis.map.qq.com weather only. Answer chosen by /root/wstub/weather.txt
import http.server, ssl, json, urllib.parse, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        u=urllib.parse.urlparse(self.path); q=urllib.parse.parse_qs(u.query)
        w=open('/root/wstub/weather.txt').read().strip()
        with open('/root/wstub/calls.log','a') as f: f.write(u.path+' location='+q.get('location',[''])[0]+' -> '+w+'\n')
        if u.path.startswith('/ws/weather/v1'):
            body={"status":0,"message":"Success","result":{"realtime":[{"province":"上海市","city":"上海市","district":"","adcode":310000,"update_time":"2026-11-05 10:00","infos":{"weather":w,"temperature":12,"wind_direction":"东风","wind_power":"3级","humidity":80}}]}}
        else:
            body={"status":311,"message":"key格式错误"}
        b=json.dumps(body,ensure_ascii=False).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self,*a): pass
s=http.server.HTTPServer(('127.0.0.1',443),H)
c=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); c.load_cert_chain('/root/wstub/c.pem','/root/wstub/k.pem')
s.socket=c.wrap_socket(s.socket,server_side=True); s.serve_forever()
