// v2-b 走查用的兜底后端:返回通用 {code:200},并给入场码一个能画出来的 21×21 PNG(只为截图,不是真码)。
import http from 'node:http';
import zlib from 'node:zlib';
const crcT = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcT[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
function qrPng() {
  const N = 21, S = 10, W = (N + 2) * S, rows = [];
  const dark = (x, y) => {
    const fin = (a, b) => x >= a && x < a + 7 && y >= b && y < b + 7;
    if (fin(0, 0) || fin(14, 0) || fin(0, 14)) { const fx = x % 7 > 3 ? 6 - (x % 7) : x % 7, fy = y % 7 > 3 ? 6 - (y % 7) : y % 7; return Math.min(fx, fy) !== 1; }
    return (x * 7 + y * 13 + x * y) % 5 < 2;
  };
  for (let py = 0; py < W; py++) {
    const row = Buffer.alloc(1 + W); row[0] = 0;
    for (let px = 0; px < W; px++) { const x = Math.floor(px / S) - 1, y = Math.floor(py / S) - 1; row[1 + px] = x >= 0 && y >= 0 && x < N && y < N && dark(x, y) ? 0 : 255; }
    rows.push(row);
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(W, 4); ihdr[8] = 8; ihdr[9] = 0;
  return 'data:image/png;base64,' + Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(Buffer.concat(rows))), chunk('IEND', Buffer.alloc(0))]).toString('base64');
}
const QR = qrPng();
const ok = (data) => JSON.stringify({ code: 200, msg: '操作成功', data });
http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    const u = req.url.split('?')[0];
    let data = null;
    if (/login\/code/.test(u)) { const m = /merchant/.test(body); res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify({ code: 200, msg: 'ok', token: 'fake-token', data: { id: 1, userType: m ? 'merchant' : 'member', role: m ? 'merchant' : 'member', nickname: '走查', avatar: '' } })); }
    else if (/dyncode\/issue/.test(u)) data = { qrcodeUrl: QR, expiresAt: Date.now() + 3600e3 };
    else if (/list|records|orders|page/.test(u)) data = [];
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(ok(data));
  });
}).listen(8099, '127.0.0.1');
