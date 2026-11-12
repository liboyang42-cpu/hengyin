// 原型逐帧:node proto-frames.mjs <protoDir> <outDir> <m> <look> <t1,t2,...>
// 时间戳从各时刻的"触发点击"算起(m4/m5 = 点支付/发送;m6 = 点卡片)。真实时钟截屏,抖动约 ±40ms。
import fs from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire('/tmp/claude-0/righome/mpweb/');
const puppeteer = require('puppeteer-core');
const [protoFile, outDir, m, look, ts] = process.argv.slice(2);
const times = ts.split(',').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const b = await puppeteer.launch({ executablePath: '/opt/pw-browsers/chromium', headless: true, args: ['--no-sandbox'] }).catch(() => puppeteer.launch({ executablePath: process.env.CHROME, headless: true, args: ['--no-sandbox'] }));
const p = await b.newPage();
await p.setViewport({ width: 1180, height: 940 });
await p.goto(`file://${protoFile}?shot=1&m=${m === "7" ? "6" : m}&look=${look}`);
await new Promise((r) => setTimeout(r, 900));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const trig = { 4: async () => { await p.evaluate(() => document.querySelector('#buy').click()); await sleep(900); await p.evaluate(() => document.querySelector('#pay').click()); },
  5: async () => { await p.evaluate(() => document.querySelector('#op').click()); await sleep(900); await p.evaluate(() => document.querySelector('#sd').click()); },
  6: async () => { await p.evaluate(() => document.querySelector('.tc').click()); },
  7: async () => { await p.evaluate(() => document.querySelector('.tc').click()); await sleep(1500); await p.evaluate(() => document.querySelector('.bk').click()); } }[m];
const t0 = Date.now();
await trig();
const phone = await p.$('.phone');
const t00 = Date.now();
for (const t of times) {
  const wait = t - (Date.now() - t00); if (wait > 0) await sleep(wait);
  await phone.screenshot({ path: `${outDir}/p-m${m}-${look}-${String(t).padStart(5, '0')}.png` });
}
await b.close();
