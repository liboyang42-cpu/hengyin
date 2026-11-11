// 把若干 390 宽截图横排成一条帧条(每帧缩到 195 宽,底部写时间戳):node strip.mjs <out.jpg> <label:file> ...
import { createRequire } from 'node:module'; const { chromium } = createRequire('/opt/node-tools/node_modules/playwright/index.js')('playwright');
import fs from 'node:fs'; import path from 'node:path';
const [out, ...items] = process.argv.slice(2);
const frames = items.map((s) => { const i = s.indexOf(':'); return { label: s.slice(0, i), file: path.resolve(s.slice(i + 1)) }; });
const html = `<body style="margin:0;background:#111;display:flex;gap:4px;padding:4px">${frames.map((f) => `<div style="display:flex;flex-direction:column;align-items:center;gap:3px"><img src="file://${f.file}" style="width:195px;height:422px;display:block"><span style="font:11px/1 monospace;color:#ddd">${f.label}</span></div>`).join('')}</body>`;
const tmp = path.resolve(out + '.html'); fs.writeFileSync(tmp, html);
const browser = await chromium.launch(); const page = await browser.newPage({ viewport: { width: 203 * frames.length + 8, height: 450 } });
await page.goto('file://' + tmp); await page.waitForFunction(() => [...document.images].every((i) => i.complete));
await page.screenshot({ path: path.resolve(out), type: 'jpeg', quality: 70, fullPage: true }); fs.unlinkSync(tmp); await browser.close(); console.log('strip', out);
