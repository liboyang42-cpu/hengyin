import http from 'node:http';
const ok = (data) => JSON.stringify({ code: 200, msg: '操作成功', data });
http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    const u = req.url.split('?')[0];
    let data = null;
    if (/login\/code/.test(u)) { const m = /merchant/.test(body); res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify({ code: 200, msg: 'ok', token: 'fake-token', data: { id: 1, userType: m ? 'merchant' : 'member', role: m ? 'merchant' : 'member', nickname: '走查', avatar: '' } })); }
    else if (/list|records|orders|page/.test(u)) data = [];
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(ok(data));
  });
}).listen(8099, '127.0.0.1');
