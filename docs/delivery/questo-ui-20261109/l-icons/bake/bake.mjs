// node bake.mjs icons|key  → 写 out/*.webp 并打印字节
import { createRequire } from 'node:module'; const { chromium } = createRequire('/opt/node-tools/node_modules/playwright/index.js')('playwright');
import fs from 'node:fs'; import path from 'node:path';
const here = path.dirname(new URL(import.meta.url).pathname), out = path.join(here, process.env.OUT || 'out'); fs.mkdirSync(out, { recursive: true });
const mode = process.argv[2] || 'icons';
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
page.on('pageerror', (e) => console.log('PAGEERR', String(e).slice(0, 300)));
await page.goto('file://' + path.join(here, 'bake.html'));
await page.waitForTimeout(500);
function save(name, url) { const b64 = url.split(',')[1]; const buf = Buffer.from(b64, 'base64'); fs.writeFileSync(path.join(out, name), buf); return buf.length; }
if (mode === 'icons') {
  const names = (process.argv[3] || 'pass,key,badge,success').split(','), px = +(process.argv[4] || 112), n = +(process.argv[5] || 24), q = +(process.argv[6] || .82), cols = +(process.argv[7] || 6);
  for (const nm of names) { const r = await page.evaluate(([nm, n, px, cols, q]) => bakeIcon(nm, n, px, cols, q), [nm, n, px, cols, q]); const bytes = save(`icon-${nm}-${px}.webp`, r.url); console.log(nm, px, n, 'frames', cols + 'x' + r.rows, bytes, 'B'); }
} else {
  const fps = +(process.argv[3] || 18), scale = +(process.argv[4] || 1), q = +(process.argv[5] || .8), cropH = +(process.argv[6] || 520), per = +(process.argv[7] || 15), cols = +(process.argv[8] || 5), idleFps = +(process.argv[9] || 12), idleS = +(process.argv[10] || 6);
  const intro = []; for (let t = 0; t <= 4600; t += 1000 / fps) intro.push({ t: Math.round(t) });
  const idleN = Math.round(idleS * idleFps), idle = []; for (let i = 0; i < idleN; i++) idle.push({ idle: i / idleN });
  await page.evaluate(() => keyReset());
  let total = 0, meta = { fps, scale, cropH, cols, intro: [], idle: [], idleFps, fw: 0, fh: 0 };
  for (let s = 0; s * per < intro.length; s++) { const fr = intro.slice(s * per, (s + 1) * per); const r = await page.evaluate(([fr, cropH, scale, cols, q]) => bakeKey(fr, cropH, scale, cols, q), [fr, cropH, scale, cols, q]); const b = save(`key-intro-${s}.webp`, r.url); total += b; meta.intro.push({ file: `key-intro-${s}.webp`, n: fr.length, bytes: b }); meta.fw = r.fw; meta.fh = r.fh; console.log('intro', s, fr.length, b); }
  for (let s = 0; s * per < idle.length; s++) { const fr = idle.slice(s * per, (s + 1) * per); const r = await page.evaluate(([fr, cropH, scale, cols, q]) => bakeKey(fr, cropH, scale, cols, q), [fr, cropH, scale, cols, q]); const b = save(`key-idle-${s}.webp`, r.url); total += b; meta.idle.push({ file: `key-idle-${s}.webp`, n: fr.length, bytes: b }); console.log('idle', s, fr.length, b); }
  fs.writeFileSync(path.join(out, 'key-meta.json'), JSON.stringify(meta, null, 1)); console.log('TOTAL', total, 'B', (total / 1024 / 1024).toFixed(2), 'MB');
}
await browser.close();
