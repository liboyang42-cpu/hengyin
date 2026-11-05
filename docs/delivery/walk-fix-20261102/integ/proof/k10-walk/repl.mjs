import http from 'node:http';
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
let w = null;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function start(opts) { if (w) { try { await w.browser.close(); } catch (e) {} } w = await launch(opts); return 'ok ' + JSON.stringify(await w.route()); }
http.createServer(async (req, res) => {
  let body = ''; req.on('data', c => body += c); await new Promise(r => req.on('end', r));
  let out;
  try {
    if (req.url === '/start') out = await start(JSON.parse(body));
    else { const mk = (await import('./helpers.mjs?t=' + Date.now())).default; const h = mk(w, sleep); const fn = new Function('w', 'sleep', 'h', 'return (async () => {' + body + '})()'); out = await fn(w, sleep, h); }
    out = typeof out === 'string' ? out : JSON.stringify(out, null, 1);
  } catch (e) { out = 'ERR ' + (e && e.stack || e); }
  res.end(String(out));
}).listen(8111, '127.0.0.1');
console.log('repl on 8111');
