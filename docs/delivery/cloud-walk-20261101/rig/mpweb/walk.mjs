// Rig driver: headless Chrome (puppeteer-core) running the real mini-program pages against the real backend.
//
// Library:
//   import { launch } from './walk.mjs';
//   const w = await launch({ path: 'pages/index/index', identity: 'e2e-openid-9001', lat: 31.23, lng: 121.47 });
//   await w.tap('报名'); await w.shot('01-home'); console.log(w.errors(), w.net());
// CLI (one command, steps as JSON):
//   node walk.mjs '{"path":"pages/index/index","identity":"e2e-openid-9001"}' '[{"wait":2500},{"shot":"home"}]'
//   node walk.mjs opts.json steps.json
// Step keys: open (url, via wx.navigateTo / switchTab / reLaunch as appropriate), relaunch, back, tap (visible text),
//   tapSel (CSS selector, pierces shadow roots), type [selector, text], wait (ms), waitText (text), shot (name),
//   scroll (px of current page), loc [lat,lng], heading (deg), scan (payload for next wx.scanCode), pay ('success'|'cancel'),
//   phone ('ok'|'deny'), modal ('confirm'|'cancel' : press a WeChat modal button), sheet (index or text), eval (JS in page),
//   login (openid: real /api/login/code via the rig seam, then reLaunch current page), dump (print visible text).
import fs from 'node:fs';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const BASE = process.env.RIG_WEB || 'http://127.0.0.1:8097';
const SHOTS = process.env.RIG_SHOTS || path.join(process.cwd(), 'shots');
const CHROME = process.env.CHROME || ['/usr/bin/google-chrome', '/opt/pw-browsers/chromium-1243/chrome-linux/chrome'].find((p) => fs.existsSync(p));

export async function launch(opts = {}) {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--autoplay-policy=no-user-gesture-required', '--lang=zh-CN'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1, isMobile: false, hasTouch: false });
  const consoleLog = [], pageErrors = [];
  page.on('console', (m) => consoleLog.push(`[${m.type()}] ${m.text()}`.slice(0, 2000)));
  page.on('response', (r) => { if (r.status() >= 400) consoleLog.push(`[error] HTTP ${r.status()} ${r.url()}`.slice(0, 400)); });
  page.on('requestfailed', (r) => consoleLog.push(`[error] FAILED ${r.url().slice(0, 300)} ${r.failure() && r.failure().errorText}`));
  page.on('pageerror', (e) => pageErrors.push(String(e && e.stack || e).slice(0, 2000)));
  const q = new URLSearchParams();
  for (const k of ['path', 'identity', 'env', 'lat', 'lng', 'heading', 'scan', 'scene']) if (opts[k] != null && opts[k] !== '') q.set(k, String(opts[k]));
  await page.goto(`${BASE}/?${q}`, { waitUntil: 'load', timeout: 120000 });
  await page.waitForFunction(() => window.MPWEB && (window.MPWEB.booted || window.MPWEB.bootError), { timeout: 60000 });
  const bootError = await page.evaluate(() => window.MPWEB.bootError || '');
  if (bootError) pageErrors.push('BOOT: ' + bootError);
  await sleep(opts.settle ?? 2000);
  const w = {
    page, browser,
    console: () => consoleLog.slice(), errors: () => pageErrors.slice(),
    net: () => page.evaluate(() => window.MPWEB.net.slice()),
    log: () => page.evaluate(() => window.MPWEB.log.slice()),
    route: () => page.evaluate(() => getCurrentPages().map((p) => p.route)),
    data: (expr) => page.evaluate((e) => { const p = getCurrentPages().slice(-1)[0]; return e ? new Function('d', 'return d.' + e)(p.data) : p.data; }, expr || ''),
    eval: (js) => page.evaluate(js),
    async open(url) {
      await page.evaluate((u) => {
        const p = u.replace(/^\//, '').split('?')[0];
        if (window.MPWEB.tabList.includes(p)) wx.switchTab({ url: '/' + p }); else wx.navigateTo({ url: u[0] === '/' ? u : '/' + u });
      }, url); await sleep(1500);
    },
    async relaunch(url) { await page.evaluate((u) => wx.reLaunch({ url: u[0] === '/' ? u : '/' + u }), url); await sleep(1500); },
    async back() { await page.evaluate(() => wx.navigateBack({})); await sleep(800); },
    async login(openid) {
      const r = await page.evaluate((o) => window.MPWEB.loginAs(o), openid);
      const cur = await w.route(); await w.relaunch('/' + cur[cur.length - 1]); return r;
    },
    async tap(text, { exact = false, nth = 0 } = {}) {
      const pt = await page.evaluate(findText, text, exact, nth);
      if (!pt) throw new Error('tap: no visible element with text ' + JSON.stringify(text));
      await page.mouse.click(pt.x, pt.y); await sleep(700); return pt;
    },
    async tapSel(sel) {
      const h = await page.$('pierce/' + sel);
      if (!h) throw new Error('tapSel: not found ' + sel);
      const b = await h.boundingBox(); if (!b) throw new Error('tapSel: not visible ' + sel);
      await page.mouse.click(b.x + b.width / 2, b.y + b.height / 2); await sleep(700);
    },
    async type(sel, text) {
      const h = await page.$('pierce/' + sel + ' input') || await page.$('pierce/' + sel + ' textarea') || await page.$('pierce/' + sel);
      if (!h) throw new Error('type: not found ' + sel);
      await h.click(); await h.type(String(text), { delay: 15 }); await sleep(300);
    },
    async waitText(text, timeout = 15000) {
      await page.waitForFunction(findText, { timeout, polling: 300 }, text, false, 0); await sleep(300);
    },
    // scrolls the page, or (when the page scrolls inside a scroll-view, as most pages here do) the largest scrollable scroll-view
    async scroll(px) {
      await page.evaluate((y) => {
        const p = getCurrentPages().slice(-1)[0]; let best = null, area = 0;
        (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if (el.scrollHeight > el.clientHeight + 4 && /auto|scroll/.test(getComputedStyle(el).overflowY)) { const r = el.getBoundingClientRect(); const a = r.width * r.height; if (a > area && r.height > 0) { area = a; best = el; } } } })(p.__root);
        if (p.__host.scrollHeight > p.__host.clientHeight + 4 && (!best || area < 390 * 400)) best = p.__host;
        (best || p.__host).scrollBy(0, y);
      }, px); await sleep(700);
    },
    async shot(name) {
      fs.mkdirSync(SHOTS, { recursive: true });
      const file = path.join(SHOTS, name.endsWith('.jpg') ? name : name + '.jpg');
      const buf = await page.screenshot({ type: 'jpeg', quality: 70, clip: { x: 0, y: 0, width: 390, height: 844 }, captureBeyondViewport: false });
      fs.writeFileSync(file, buf); return file;
    },
    async text() { return page.evaluate(visibleText); },
    async loc(lat, lng) { await page.evaluate((a, b) => window.MPWEB.setLocation(a, b), lat, lng); await sleep(300); },
    async heading(d) { await page.evaluate((x) => window.MPWEB.setHeading(x), d); },
    async scan(payload) { await page.evaluate((p) => window.MPWEB.setScan(p), payload); },
    async pay(mode) { await page.evaluate((m) => { window.MPWEB.payDecision = m; }, mode); },
    async phone(mode) { await page.evaluate((m) => { window.MPWEB.phoneSeam = m; }, mode); },
    async modal(which) { await page.evaluate((wh) => { const b = document.querySelectorAll('.wx-modal__btn'); const t = wh === 'cancel' ? [...b].find((x) => !x.classList.contains('is-ok')) : document.querySelector('.wx-modal__btn.is-ok'); if (!t) throw new Error('no modal'); t.click(); }, which); await sleep(700); },
    async sheet(which) { await page.evaluate((wh) => { const b = [...document.querySelectorAll('.wx-as__i')]; const t = typeof wh === 'number' ? b[wh] : b.find((x) => x.textContent.includes(wh)); if (!t) throw new Error('no sheet item'); t.click(); }, which); await sleep(700); },
    async close() { await browser.close(); },
  };
  // opts.agree: pass the real privacy gate (tick + 同意并继续) the way a person would, if it is showing
  if (opts.agree) {
    try { if (await page.evaluate(findText, '同意并继续', false, 0)) { await w.tap('我已阅读并同意'); await w.tap('同意并继续'); await sleep(1200); } } catch (e) { pageErrors.push('agree: ' + e.message); }
  }
  return w;
}

// Runs inside the page: find the innermost visible element whose own text contains `text` (pierces shadow roots).
function findText(text, exact, nth) {
  const hits = [];
  const seen = new Set();
  function visible(el) { const r = el.getBoundingClientRect(); if (r.width < 1 || r.height < 1) return null; if (r.bottom < 0 || r.top > 844 || r.right < 0 || r.left > 390) return null; const cs = getComputedStyle(el); if (cs.visibility === 'hidden' || cs.display === 'none' || +cs.opacity === 0) return null; return r; }
  function walk(root) {
    const all = root.querySelectorAll('*');
    for (const el of all) {
      if (el.shadowRoot) walk(el.shadowRoot);
      if (seen.has(el)) continue; seen.add(el);
      let own = ''; for (const n of el.childNodes) if (n.nodeType === 3) own += n.data;
      own = own.replace(/\s+/g, ' ').trim();
      if (!own && el.tagName === 'INPUT') own = el.placeholder || '';
      if (!own) continue;
      if (exact ? own === text : own.includes(text)) { const r = visible(el); if (r) hits.push(r); }
    }
  }
  walk(document);
  const r = hits[nth]; if (!r) return null;
  const x = r.left + r.width / 2, y = r.top + r.height / 2;
  return { x, y };
}
function visibleText() {
  const out = [];
  function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); let own = ''; for (const n of el.childNodes) if (n.nodeType === 3) own += n.data; own = own.replace(/\s+/g, ' ').trim(); if (!own) continue; const r = el.getBoundingClientRect(); if (r.width < 1 || r.height < 1 || r.bottom < 0 || r.top > 844) continue; const cs = getComputedStyle(el); if (cs.visibility === 'hidden' || +cs.opacity === 0) continue; out.push(own); } }
  walk(document); return out.join(' | ');
}
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function runSteps(w, steps) {
  for (const s of steps) {
    const [k, v] = Object.entries(s)[0];
    const label = `${k} ${JSON.stringify(v)}`;
    try {
      if (k === 'open') await w.open(v);
      else if (k === 'relaunch') await w.relaunch(v);
      else if (k === 'back') await w.back();
      else if (k === 'tap') await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v));
      else if (k === 'tapSel') await w.tapSel(v);
      else if (k === 'type') await w.type(v[0], v[1]);
      else if (k === 'wait') await sleep(v);
      else if (k === 'waitText') await w.waitText(v);
      else if (k === 'shot') console.log('shot', await w.shot(v));
      else if (k === 'scroll') await w.scroll(v);
      else if (k === 'loc') await w.loc(v[0], v[1]);
      else if (k === 'heading') await w.heading(v);
      else if (k === 'scan') await w.scan(v);
      else if (k === 'pay') await w.pay(v);
      else if (k === 'phone') await w.phone(v);
      else if (k === 'modal') await w.modal(v);
      else if (k === 'sheet') await w.sheet(v);
      else if (k === 'login') console.log('login', JSON.stringify(await w.login(v)));
      else if (k === 'eval') console.log('eval', JSON.stringify(await w.eval(v)));
      else if (k === 'dump') console.log('text:', await w.text());
      else throw new Error('unknown step ' + k);
      console.log('ok  ', label);
    } catch (e) { console.log('FAIL', label, '-', e.message); }
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const arg = (a, d) => (a == null ? d : (fs.existsSync(a) ? JSON.parse(fs.readFileSync(a, 'utf8')) : JSON.parse(a)));
  const opts = arg(process.argv[2], {}), steps = arg(process.argv[3], [{ shot: 'snapshot' }]);
  const w = await launch(opts);
  console.log('route', JSON.stringify(await w.route()));
  await runSteps(w, steps);
  console.log('route', JSON.stringify(await w.route()));
  const net = await w.net();
  console.log('--- network (' + net.length + ')'); for (const n of net) console.log(n.status, n.ms + 'ms', n.method, n.url.slice(0, 140));
  const errs = w.errors(); console.log('--- page errors (' + errs.length + ')'); errs.slice(0, 20).forEach((e) => console.log(e.split('\n').slice(0, 3).join(' / ')));
  const cons = w.console().filter((c) => /^\[(error|warn)/.test(c)); console.log('--- console errors/warnings (' + cons.length + ')'); cons.slice(0, 30).forEach((c) => console.log(c.slice(0, 300)));
  const log = await w.log(); console.log('--- runtime log (' + log.length + ')'); log.slice(0, 30).forEach((c) => console.log(c.slice(0, 200)));
  await w.close();
}
