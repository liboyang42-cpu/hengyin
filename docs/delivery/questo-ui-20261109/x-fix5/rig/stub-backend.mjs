// 预览取证用最小后端桩:/api/login/code 返回固定玩家,其余一律 200 空数据。只用于 play 预览(applyPreviewData),不模拟任何业务。
import http from 'node:http';
http.createServer((req, res) => {
  let b = ''; req.on('data', c => b += c); req.on('end', () => {
    res.setHeader('content-type', 'application/json');
    if (req.url.startsWith('/api/login/code')) return res.end(JSON.stringify({ code: 200, token: 'stub-token', data: { id: 990050, userType: 0, role: 'player', nickname: '体验玩家', avatar: '' } }));
    res.end(JSON.stringify({ code: 200, msg: 'ok', data: {}, rows: [], total: 0 }));
  });
}).listen(8099, '127.0.0.1');
