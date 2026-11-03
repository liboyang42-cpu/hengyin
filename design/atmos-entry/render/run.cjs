#!/usr/bin/env node
// Renders atmos.mp4 + sheet.jpg. Needs: Node 18+, Playwright with Chromium, ffmpeg.
// Usage: node run.cjs [--scale 0.5] [--frames 0,30,60] [--out ../] [--reprep] [--sun x,y,z] [--exposure 1] [--sunE 9]
const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');

const HERE = __dirname;
// NASA's own servers (visibleearth / eoimages / neo) are blocked from the render container, so the same
// public-domain NASA files are fetched from the GitHub mirrors below and pinned by SHA-256. CREDITS.md lists both.
const SOURCES = [
  { file: 'bmng21600.jpg', sha: '3006c58b1272362db0a8c2df02dc07cea4b12dfe820b7dc4a159a075caf5d4d4',
    url: 'https://raw.githubusercontent.com/stdcout1/airline3d/master/airports/world.topo.bathy.200412.3x21600x10800.jpg' },
  { file: 'clouds8192.png', sha: '396bee0a1be4284a9632806b950df1d0c8a66ed4b849f78ec23e361c57932c37',
    url: 'https://media.githubusercontent.com/media/Prograda/Skybolt/master/Assets/Globe/Environment/Cloud/cloud_combined_8192.png' },
  { file: 'bm2016_3km_gray.jpg', sha: '3fbc8aae3529dc1ebfcf3aa9361bd3dfc11d19950a6af6d8ed2df61bd8b0134d',
    url: 'https://media.githubusercontent.com/media/VeljkoS2/3D-Orbital-Physics-Engine/main/Assets/Planets%20of%20the%20Solar%20System%203D/Materials/earth/BlackMarble_2016_3km_gray.jpg' },
  { file: 'bsc5.json', sha: '94b0581379ef9ea49f1ce664734a06d2fbbff2d7487f926acad3e9ef0960e0d8',
    url: 'https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-short.json' },
];

const argv = process.argv.slice(2);
const arg = (k, d) => { const i = argv.indexOf('--' + k); return i >= 0 ? argv[i + 1] : d; };
const flag = (k) => argv.includes('--' + k);
const scale = Number(arg('scale', '1'));
const outDir = path.resolve(arg('out', path.join(HERE, '..')));
const srcDir = path.join(HERE, '.cache', 'nasa');
const framesDir = path.resolve(arg('frames-dir', path.join(HERE, '.cache', 'frames' + (scale === 1 ? '' : '-' + scale))));
const texDir = path.join(HERE, '.cache', 'textures');
const only = arg('frames') ? arg('frames').split(',').map(Number) : null;
const W = Math.round((780 * scale) / 2) * 2;
const H = Math.round((1688 * scale) / 2) * 2;

async function ensureData() {
  fs.mkdirSync(srcDir, { recursive: true });
  for (const s of SOURCES) {
    const p = path.join(srcDir, s.file);
    if (!fs.existsSync(p)) {
      console.log('fetch', s.url);
      const r = await fetch(s.url);
      if (!r.ok) throw new Error(s.url + ' ' + r.status);
      fs.writeFileSync(p, Buffer.from(await r.arrayBuffer()));
    }
    const sha = crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
    if (sha !== s.sha) throw new Error(`${s.file}: sha256 ${sha} does not match pinned ${s.sha}`);
  }
  if (flag('reprep') || !fs.existsSync(path.join(texDir, 'stars.json'))) {
    // needs Pillow + numpy; use a venv if the system Python lacks them
    const py = fs.existsSync(path.join(HERE, '.cache', 'venv', 'bin', 'python')) ? path.join(HERE, '.cache', 'venv', 'bin', 'python') : 'python3';
    execFileSync(py, [path.join(HERE, 'prep.py')], { stdio: 'inherit' });
  }
}

function serve() {
  const three = path.join(path.dirname(require.resolve('three', { paths: [HERE] })), 'three.module.js');
  const types = { '.png': 'image/png', '.js': 'text/javascript', '.html': 'text/html', '.json': 'application/json' };
  const srv = http.createServer((req, res) => {
    const u = decodeURIComponent(req.url.split('?')[0]);
    let file;
    if (u === '/vendor/three.module.js') file = three;
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
  const num = (k) => (arg(k) === undefined ? undefined : Number(arg(k)));
  const info = await page.evaluate((o) => window.__boot(o), { width: W, height: H, cloudScale: Number(arg('cloud-scale', '1')), noiseN: 96,
    sun: arg('sun') ? arg('sun').split(',').map(Number) : undefined, exposure: num('exposure'), sunE: num('sunE'), starYaw: num('star-yaw'), starTilt: num('star-tilt'), noBoost: flag('no-boost') });
  console.log('setup', ((Date.now() - t0) / 1000).toFixed(1) + 's', info);
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
