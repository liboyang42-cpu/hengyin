// Rig web server (127.0.0.1:8097):
//   /                 shell.html (phone frame + runtime)
//   /build/*          compiled bundle ($RIG_HOME/mpweb/bundle.js, appjson.js)
//   /src/*            runtime sources from this directory
//   /mp/*             mini-program static assets (images, fonts) from the candidate chengyinhub-xcx
//   /__api/*          reverse proxy to the real backend 127.0.0.1:8099 (the app's prod base URL is rewritten here)
//   /sns/jscode2session  [SEAM] fake WeChat code2session: js_code "rig-<openid>" -> {openid}
//   /__rig/pay        [SEAM] payment success through the real POST /api/registration/callback (pay.py)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RIG_HOME = process.env.RIG_HOME || path.join(process.env.HOME, 'walk-rig');
const CAND = process.env.CAND || path.join(RIG_HOME, 'candidate');
const XCX = path.join(CAND, 'chengyinhub-xcx');
const BUILD = path.join(RIG_HOME, 'mpweb');
const PORT = Number(process.env.RIG_WEB_PORT || 8097);
const BACKEND = { host: '127.0.0.1', port: 8099 };
const PY = path.join(RIG_HOME, 'venv', 'bin', 'python');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.json': 'application/json', '.ttf': 'font/ttf', '.woff': 'font/woff', '.woff2': 'font/woff2', '.mp3': 'audio/mpeg', '.otf': 'font/otf' };

function sendFile(res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, { 'content-type': TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream', 'cache-control': 'no-store' });
    fs.createReadStream(file).pipe(res);
  });
}
function safeJoin(root, rel) { const p = path.normalize(path.join(root, decodeURIComponent(rel))); return p.startsWith(root) ? p : null; }

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  if (p === '/' || p === '/index.html') return sendFile(res, path.join(HERE, 'shell.html'));
  if (p.startsWith('/src/')) { const f = safeJoin(path.join(HERE, 'src'), p.slice(5)); return f ? sendFile(res, f) : res.end(); }
  if (p.startsWith('/build/')) { const f = safeJoin(BUILD, p.slice(7)); return f ? sendFile(res, f) : res.end(); }
  if (p.startsWith('/mp/')) { const f = safeJoin(XCX, p.slice(4)); return f ? sendFile(res, f) : res.end(); }
  if (p === '/sns/jscode2session') {
    const code = url.searchParams.get('js_code') || '';
    res.writeHead(200, { 'content-type': 'application/json' });
    if (!code.startsWith('rig-') || code.length < 6) return res.end(JSON.stringify({ errcode: 40029, errmsg: 'invalid code (rig expects rig-<openid>)' }));
    return res.end(JSON.stringify({ openid: code.slice(4), session_key: 'rig-session-key' }));
  }
  if (p === '/__rig/pay' && req.method === 'POST') {
    let body = ''; req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let memberId = ''; try { memberId = String(JSON.parse(body || '{}').memberId || ''); } catch (e) {}
      const child = spawn(PY, [path.join(HERE, '..', 'pay.py'), '--member', memberId, '--json'], { env: process.env });
      let out = ''; child.stdout.on('data', (d) => { out += d; }); child.stderr.on('data', (d) => { out += d; });
      child.on('close', () => { res.writeHead(200, { 'content-type': 'application/json' }); const line = out.trim().split('\n').pop(); try { JSON.parse(line); res.end(line); } catch (e) { res.end(JSON.stringify({ ok: false, error: out.slice(-500) })); } });
    });
    return;
  }
  if (p.startsWith('/__api/')) {
    const target = p.slice(6) + url.search;
    const headers = Object.assign({}, req.headers, { host: `${BACKEND.host}:${BACKEND.port}` });
    delete headers.origin; delete headers.referer;
    const preq = http.request({ ...BACKEND, method: req.method, path: target, headers }, (pres) => {
      res.writeHead(pres.statusCode, pres.headers); pres.pipe(res);
    });
    preq.on('error', (e) => { res.writeHead(502); res.end(JSON.stringify({ code: 502, msg: 'rig proxy: backend unreachable ' + e.message })); });
    req.pipe(preq);
    return;
  }
  res.writeHead(404); res.end('not found');
});
server.listen(PORT, '127.0.0.1', () => console.log(`rig web on http://127.0.0.1:${PORT}`));
