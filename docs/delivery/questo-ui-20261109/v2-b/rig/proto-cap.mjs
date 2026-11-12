// 原型截帧:node proto-cap.mjs <proto index.html> <moment 2|7> <look> <outDir>  (puppeteer-core 取自 rig 的 node_modules)
import fs from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const puppeteer = require(process.env.PUP || 'puppeteer-core');
const [file, moment, look, outDir] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const CHROME = ['/usr/bin/google-chrome', '/opt/pw-browsers/chromium-1243/chrome-linux/chrome'].find((p) => fs.existsSync(p));
const b = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--lang=zh-CN'] });
const page = await b.newPage();
await page.setViewport({ width: 480, height: 940, deviceScaleFactor: 1 });
await page.goto(`file://${file}?m=${moment}&look=${look}`, { waitUntil: 'load' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const phone = async () => (await page.$('.phone'));
const shot = async (tag) => { await (await phone()).screenshot({ path: `${outDir}/m${moment}-${look}-${tag}.jpg`, type: 'jpeg', quality: 80 }); };
await sleep(1500);
if (moment === '2') {
  await shot('t0000-idle'); await sleep(1500); await shot('t0001-idle-later');
  await page.evaluate(() => document.querySelector('#pk').click());
  const t0 = Date.now();
  for (const off of [130, 400, 700, 1000, 1400, 1900, 2500, 3400]) { const w = off - (Date.now() - t0); if (w > 0) await sleep(w); await shot('t' + String(off).padStart(4, '0')); }
} else {
  // 原型自带演示序列(700ms 点 ⋯,再 1100ms 点入场码);这里等它跑完前先截主面,再手动重放
  await sleep(300);
  await page.evaluate(() => { const mn = document.querySelector('#mn'), dim = document.querySelector('.dim'), sh = document.querySelector('#sh'); mn.classList.remove('on'); sh.style.transform = 'translateY(110%)'; dim.style.opacity = 0; });
  await sleep(900); await shot('t0000-play');
  await page.evaluate(() => document.querySelector('#mo').click());
  let t0 = Date.now();
  for (const off of [60, 160, 300, 520, 900]) { const w = off - (Date.now() - t0); if (w > 0) await sleep(w); await shot('menu-t' + String(off).padStart(4, '0')); }
  await page.evaluate(() => document.querySelector('#mn .mi').click());
  t0 = Date.now();
  for (const off of [150, 400, 800, 1600]) { const w = off - (Date.now() - t0); if (w > 0) await sleep(w); await shot('ticket-t' + String(off).padStart(4, '0')); }
}
await b.close();
