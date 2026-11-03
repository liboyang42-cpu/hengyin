#!/usr/bin/env node
// Renders atmos.mp4 + sheet.jpg. Needs: Node 18+, Playwright with Chromium, ffmpeg.
// Usage: node run.cjs [--scale 0.5] [--frames 0,30,60] [--out ../] [--data ./.cache/ne] [--repaint]
const fs = require('fs');
const path = require('path');
const http = require('http');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');

const HERE = __dirname;
const NE_COMMIT = 'ca96624a56bd078437bca8184e78163e5039ad19';
const NE_FILES = ['ne_50m_land', 'ne_50m_lakes', 'ne_50m_geography_regions_polys', 'ne_50m_glaciated_areas', 'ne_10m_populated_places_simple'];

const argv = process.argv.slice(2);
const arg = (k, d) => { const i = argv.indexOf('--' + k); return i >= 0 ? argv[i + 1] : d; };
const flag = (k) => argv.includes('--' + k);
const scale = Number(arg('scale', '1'));
const outDir = path.resolve(arg('out', path.join(HERE, '..')));
const dataDir = path.resolve(arg('data', path.join(HERE, '.cache', 'ne')));
const framesDir = path.resolve(arg('frames-dir', path.join(HERE, '.cache', 'frames' + (scale === 1 ? '' : '-' + scale))));
const texDir = path.join(HERE, '.cache', 'textures');
const only = arg('frames') ? arg('frames').split(',').map(Number) : null;
const W = Math.round((780 * scale) / 2) * 2;
const H = Math.round((1688 * scale) / 2) * 2;

async function ensureData() {
  fs.mkdirSync(dataDir, { recursive: true });
  for (const f of NE_FILES) {
    const p = path.join(dataDir, f + '.geojson');
    if (fs.existsSync(p)) continue;
    const url = `https://raw.githubusercontent.com/nvkelso/natural-earth-vector/${NE_COMMIT}/geojson/${f}.geojson`;
    console.log('fetch', url);
    const r = await fetch(url);
    if (!r.ok) throw new Error(url + ' ' + r.status);
    fs.writeFileSync(p, Buffer.from(await r.arrayBuffer()));
  }
}

function serve() {
  const three = path.join(path.dirname(require.resolve('three', { paths: [HERE] })), 'three.module.js');
  const types = { '.png': 'image/png', '.js': 'text/javascript', '.html': 'text/html', '.geojson': 'application/json' };
  const srv = http.createServer((req, res) => {
    const u = decodeURIComponent(req.url.split('?')[0]);
    let file;
    if (u === '/vendor/three.module.js') file = three;
    else if (u.startsWith('/data/')) file = path.join(dataDir, path.basename(u));
    else if (u.startsWith('/tex/')) file = path.join(texDir, path.basename(u));
    else file = path.join(HERE, path.basename(u === '/' ? 'index.html' : u));
    fs.readFile(file, (err, buf) => {
      if (err) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
      res.end(buf);
    });
  });
  return new Promise((ok) => srv.listen(0, '127.0.0.1', () => ok(srv)));
}

(async () => {
  await ensureData();
  fs.mkdirSync(framesDir, { recursive: true });
  const srv = await serve();
  const port = srv.address().port;
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--disable-gpu-watchdog', '--disable-features=GpuWatchdog'] });
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  page.on('console', (m) => console.log(m.text()));
  page.on('pageerror', (e) => console.error('pageerror', e));
  page.setDefaultTimeout(0);
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction(() => window.__ready);
  const t0 = Date.now();
  // Painting the textures takes ~15 min in software Chrome; they are cached as PNG and reused.
  const haveTex = !flag('repaint') && fs.existsSync(path.join(texDir, 'china_mask.png'));
  const info = await page.evaluate((o) => window.__boot(o), { width: W, height: H, cloudScale: 0.5, dumpTextures: !haveTex, cachedTextures: haveTex, texW: 4096, noiseN: 96 });
  console.log('setup', ((Date.now() - t0) / 1000).toFixed(1) + 's', info);
  if (!haveTex) {
    const tex = await page.evaluate(() => window.__tex);
    fs.mkdirSync(texDir, { recursive: true });
    for (const [k, v] of Object.entries(tex)) fs.writeFileSync(path.join(texDir, k + '.png'), Buffer.from(v.split(',')[1], 'base64'));
  }
  const list = only || [...Array(info.frames).keys()];
  for (const i of list) {
    const t1 = Date.now();
    const url = await page.evaluate((f) => window.__render(f), i);
    fs.writeFileSync(path.join(framesDir, `f${String(i).padStart(4, '0')}.png`), Buffer.from(url.split(',')[1], 'base64'));
    console.log(`frame ${i} ${((Date.now() - t1) / 1000).toFixed(1)}s`);
  }
  await browser.close();
  srv.close();
  if (only || flag('no-encode')) return;

  fs.mkdirSync(outDir, { recursive: true });
  const mp4 = path.join(outDir, 'atmos.mp4');
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(info.fps), '-i', path.join(framesDir, 'f%04d.png'),
    '-c:v', 'libx264', '-profile:v', 'high', '-level', '4.0', '-pix_fmt', 'yuv420p', '-preset', 'veryslow', '-crf', arg('crf', '19'),
    '-x264-params', 'aq-mode=3:deblock=-1,-1', '-an', '-movflags', '+faststart', mp4], { stdio: 'inherit' });
  // 16-frame contact sheet sampled evenly from the encoded video
  const n = info.frames;
  const pick = [...Array(16).keys()].map((k) => Math.round((k * (n - 1)) / 15));
  const sel = pick.map((f) => `eq(n\\,${f})`).join('+');
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', mp4, '-vf', `select='${sel}',scale=260:-2,tile=8x2:padding=6:color=0x202020`, '-frames:v', '1', '-q:v', '3', path.join(outDir, 'sheet.jpg')], { stdio: 'inherit' });
  console.log('size', fs.statSync(mp4).size, 'bytes');
})().catch((e) => { console.error(e); process.exit(1); });
