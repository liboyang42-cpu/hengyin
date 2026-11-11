// node sheet.mjs out.jpg cols label1,label2.. img1 img2 ... → 把若干 390x844 截图拼成一张联系表(按列数换行,图下标注名)
import { createRequire } from 'node:module'; import fs from 'node:fs'; import path from 'node:path';
const require = createRequire(process.env.RIG_HOME + '/mpweb/'); const puppeteer = require('puppeteer-core');
const [out, cols, ...files] = process.argv.slice(2); const C = +cols; const sc = 0.5;
const html = `<body style="margin:0;background:#444;display:grid;grid-template-columns:repeat(${C},${390 * sc}px);gap:4px;padding:4px;font:12px sans-serif;color:#fff">` +
  files.map(f => `<div><img style="width:${390 * sc}px;display:block" src="data:image/jpeg;base64,${fs.readFileSync(f).toString('base64')}"><div>${path.basename(f, '.jpg')}</div></div>`).join('') + '</body>';
const b = await puppeteer.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
const p = await b.newPage(); await p.setViewport({ width: C * (390 * sc + 4) + 4, height: 300 }); await p.setContent(html);
await p.screenshot({ path: out, type: 'jpeg', quality: 80, fullPage: true }); await b.close();
