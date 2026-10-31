// 截图:每个原型的每一屏,打开「显示改动」,只截手机。用法:node tools/shoot.js [p1-posts.html ...]
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const ROOT = path.resolve(__dirname, '..');
const pages = process.argv.slice(2).length ? process.argv.slice(2) : ['p1-posts.html', 'p2-profile.html', 'p3-achievements.html', 'p4-theme-publish.html', 'p5-merchant.html'];
(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 2 });
  const out = [];
  for (const file of pages) {
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(e.message));
    await page.goto('file://' + path.join(ROOT, file) + '?mk=1');
    await page.waitForTimeout(600);
    const ids = await page.$$eval('.scr', (els) => els.map((e) => e.getAttribute('data-id')));
    const tag = file.split('-')[0];
    for (const id of ids) {
      await page.evaluate((id) => PROTO.show(id), id);
      await page.waitForTimeout(450);
      await page.evaluate((id) => (window.SHOT ? window.SHOT(id) : null), id);
      await page.waitForTimeout(80);
      const f = path.join(ROOT, 'shots', id + '.png');
      await page.locator('#phone').screenshot({ path: f });
      out.push(path.relative(ROOT, f));
    }
    if (errs.length) console.log(file, 'ERRORS', errs);
    await page.close();
  }
  fs.writeFileSync(path.join(ROOT, 'shots', 'list.txt'), out.join('\n') + '\n');
  console.log(out.length, 'shots');
  await browser.close();
})();
