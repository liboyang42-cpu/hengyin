import { createRequire } from 'node:module'; const { chromium } = createRequire('/opt/node-tools/node_modules/playwright/index.js')('playwright');
import path from 'node:path'; import fs from 'node:fs';
const files = process.argv.slice(2); const browser = await chromium.launch(); const page = await browser.newPage({ viewport: { width: 1400, height: 1200 } });
for (const f of files) { const abs = path.resolve(f); const html = abs + '.html'; fs.writeFileSync(html, `<body style="margin:0;background:#15161a"><img id=i src="file://${abs}" style="display:block;max-width:1400px"></body>`); await page.goto('file://' + html); await page.waitForFunction(() => document.getElementById('i').complete); const el = await page.$('#i'); await el.screenshot({ path: abs.replace(/\.webp$/, '.png') }); fs.unlinkSync(html); console.log('ok', path.basename(abs)); }
await browser.close();
