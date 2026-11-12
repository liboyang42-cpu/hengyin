// v2-a 对比截图:同一时刻、同一外观,分别从原型(proto)与步行 rig 的真实 play 页抓图。
// 用法(rig 需先起 mpweb/server.mjs;puppeteer-core 经 NODE_PATH 解析):
//   NODE_PATH=<rig>/mpweb/node_modules node capture.cjs <proto|protoreal|rig> <1|3> <stardust|paper|survival|cyber> <outDir> [protoHtml]
// protoreal = 原型,但外观色换成 pages/play/look-tokens.wxss 的真实 --lk-* 值(原型自带的是示意色),用来隔离「版面 / 尺寸 / 时序」与「色值」两类差异。
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');
const [, , target, moment, look, outDir, protoHtml] = process.argv;
const TIMES = { 1: [300, 1100, 2000, 2800], 3: [500, 1000, 1600, 2600] };
const REAL = {
  stardust: { bg: '#0A0A0A', ink: '#F2F2F0', a: '#E9E2D0', b: '#9AA0AA' },
  paper: { bg: '#1D1B18', ink: '#ECE6D8', a: '#D0503C', b: '#9A927F' },
  survival: { bg: '#0E1012', ink: '#E4E7E9', a: '#FF8A3D', b: '#8A9196' },
  cyber: { bg: '#0B0B0F', ink: '#ECEBE4', a: '#F3E600', b: '#24E0E8' },
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const mix = (c1, c2, t) => '#' + hex(c1).map((v, i) => Math.round(v * (1 - t) + hex(c2)[i] * t).toString(16).padStart(2, '0')).join('');
const INFO = {
  1: { number: '01', kicker: '第 1 章 / 共 2 章', title: '第一章 · 星尘落点', stats: [{ v: '05:56', k: '用时' }, { v: '2/2', k: '坐标' }, { v: '0.6', k: '公里' }] },
  3: { kicker: '已通关 · 11月12日', title: '纸本旧书 · 人民广场寻字', stats: [{ v: '4/4', k: '坐标' }, { v: '27:00', k: '用时' }, { v: '0.5', k: '公里' }] },
};

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  let browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--lang=zh-CN'] });
  let page = await browser.newPage();
  const times = TIMES[moment];
  const log = [];
  if (target === 'rig') {
    // 用 rig 自带 driver 开页并过隐私授权门(真人一样的勾选 + 同意并继续)
    const { launch } = await import(process.env.RIG_WALK);
    await browser.close();
    var w = await launch({ path: 'pages/play/index', agree: true, settle: 2500 });
    page = w.page; browser = w.browser;
    const route = await page.evaluate(() => getCurrentPages().map((p) => p.route));
    log.push('route ' + route.join(','));
    // 先把 look 套上,再单独触发时刻(量时从触发那一刻起)
    await page.evaluate((lk) => {
      const p = getCurrentPages().slice(-1)[0];
      p.setData({ lookClass: 'look--' + lk, reducedMotion: false, emptyTip: '', emptyKind: '', loading: false });
    }, look);
    await sleep(400);
    const info = INFO[moment];
    await page.evaluate((m, info) => {
      const p = getCurrentPages().slice(-1)[0];
      window.__t0 = performance.now();
      if (m === 1) p.setData({ chapterFull: true, chapterGate: { show: true, held: false, number: 1, total: 2, title: '', subtitle: '', faces: [], endInfo: null, info, look: null } });
      else p.setData({ showFinish: true, finale: { show: true, faces: [], info } });
    }, Number(moment), info);
    for (const t of times) {
      const now = await page.evaluate(() => performance.now() - window.__t0);
      if (now < t) await sleep(Math.max(0, t - now - 4));
      const at = await page.evaluate(() => performance.now() - window.__t0);
      await page.screenshot({ path: path.join(outDir, `rig-m${moment}-${look}-${t}.png`), clip: { x: 0, y: 0, width: 390, height: 844 } });
      log.push(`t=${t} actual=${Math.round(at)}`);
    }
    const errs = await page.evaluate(() => (window.MPWEB && window.MPWEB.log ? window.MPWEB.log.slice(-5) : []));
    log.push('mplog ' + JSON.stringify(errs).slice(0, 400));
  } else {
    await page.setViewport({ width: 1320, height: 1000, deviceScaleFactor: 1 });
    const q = `?shot=1&m=${moment}&look=${look}`;
    await page.goto('file://' + path.resolve(protoHtml) + q, { waitUntil: 'load' });
    await page.addStyleTag({ content: '.phone{border-radius:0 !important;box-shadow:none !important} .sb,.island,.capsule,.home,.m1 .share{display:none !important}' });
    if (target === 'protoreal') {
      const r = REAL[look];
      await page.addStyleTag({ content: `:root[data-look="${look}"]{--lk-bg:${r.bg};--lk-bg2:${mix(r.bg, r.a, 0.12)};--lk-a:${r.a};--lk-b:${r.b};--lk-ink:${r.ink};--lk-on:${r.bg}}` });
    }
    await sleep(200);
    await page.evaluate((m) => { window.__t0 = performance.now(); render(m); }, Number(moment));
    const box = await page.evaluate(() => { const r = document.querySelector('.phone').getBoundingClientRect(); return { x: r.x, y: r.y + window.scrollY, width: r.width, height: r.height }; });
    for (const t of times) {
      const now = await page.evaluate(() => performance.now() - window.__t0);
      if (now < t) await sleep(Math.max(0, t - now - 4));
      const at = await page.evaluate(() => performance.now() - window.__t0);
      await page.screenshot({ path: path.join(outDir, `${target}-m${moment}-${look}-${t}.png`), clip: { x: box.x, y: box.y, width: 390, height: 844 } });
      log.push(`t=${t} actual=${Math.round(at)}`);
    }
  }
  console.log(target, moment, look, log.join(' | '));
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
