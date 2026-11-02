// node fw.mjs <pages.json> <outdir>   (RIG_WEB selects the bundle server: 8097 = after, 8098 = before)
// For each page: open in the rig as the given identity, run steps, screenshot, collect errors and layout facts
// (measure-merchant/tools/measure.js) and a few regression probes (undefined/null/NaN text, empty headings, tiny tap targets).
import fs from 'node:fs';
import path from 'node:path';
const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const MJS = fs.readFileSync(process.env.MEASURE_JS || (process.env.HOME + '/measure-tools/measure.js'), 'utf8');
const [pf, out] = process.argv.slice(2);
const only = process.env.ONLY ? process.env.ONLY.split(',') : null;
fs.mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
for (const P of JSON.parse(fs.readFileSync(pf, 'utf8'))) {
  if (only && !only.includes(P.name)) continue;
  const res = { name: P.name, path: P.path };
  let w;
  try {
    w = await launch({ path: P.start || P.path, identity: P.id || 'e2e-openid-9009', agree: true, settle: 2500, lat: P.lat, lng: P.lng });
    if (P.start) { await w.open(P.path); await sleep(2500); }
    for (const s of P.steps || []) {
      const [k, v] = Object.entries(s)[0];
      try {
        if (k === 'wait') await sleep(v);
        else if (k === 'eval') res.evalOut = await w.eval(v);
        else if (k === 'tap') await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v));
        else if (k === 'tapSel') await w.tapSel(v);
        else if (k === 'open') { await w.open(v); await sleep(2500); }
        else if (k === 'scroll') await w.scroll(v);
        else if (k === 'shot') fs.writeFileSync(path.join(out, P.name + '--' + v + '.png'), await w.page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 390, height: 844 } }));
      } catch (e) { (res.stepErr ||= []).push(k + ': ' + e.message.slice(0, 160)); }
    }
    await sleep(P.settle || 1800);
    res.route = (await w.route()).slice(-1)[0];
    fs.writeFileSync(path.join(out, P.name + '.png'), await w.page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 390, height: 844 } }));
    await w.page.evaluate(MJS);
    res.probe = await w.page.evaluate(() => {
      const p = getCurrentPages().slice(-1)[0];
      const bad = [], emptyHead = [], tiny = [], titles = [];
      let theme = getComputedStyle(p.__host).backgroundColor;
      (function walk(root) { for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) walk(el.shadowRoot);
        const r = el.getBoundingClientRect(); if (r.width < 1 || r.height < 1 || r.top > 844 || r.bottom < 0) continue;
        const cs = getComputedStyle(el); if (cs.visibility === 'hidden' || cs.display === 'none') continue;
        let own = ''; for (const n of el.childNodes) if (n.nodeType === 3) own += n.data; own = own.trim();
        if (/\b(undefined|null|NaN)\b|\[object/.test(own)) bad.push(own.slice(0, 40));
        const c = el.getAttribute('class') || '';
        if (/(^|\s)[\w-]*(title|head|sec-t)[\w-]*($|\s)/i.test(c) && !el.shadowRoot && !(el.textContent || '').trim() && !el.querySelector('image,wx-image,img,canvas') && r.height > 8) emptyHead.push(c.slice(0, 40));
        if ((el.getAttribute('aria-role') === 'button' || el.tagName === 'WX-BUTTON') && (r.height < 30 || r.width < 30)) tiny.push(c.slice(0, 30) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
        if (el.tagName === 'CY-PAGE-TITLE' || el.tagName === 'WX-CY-PAGE-TITLE' || /cy-page-title/i.test(el.tagName)) titles.push({ top: r.top, bottom: r.bottom });
      } })(p.__root);
      const bg = getComputedStyle(document.querySelector('mp-page') || p.__host).backgroundColor;
      return { bad: [...new Set(bad)].slice(0, 8), emptyHead: [...new Set(emptyHead)].slice(0, 8), tiny: [...new Set(tiny)].slice(0, 8), bg, theme, titles };
    });
    try {
      const m = await w.page.evaluate(() => window.__measure());
      const top = m.cards.filter((c) => !c.nested && c.w < 388 && c.w > 150);
      // title → first block: the cy-page-title ink bottom to the next section's ink top
      let tgap = null; const S = m.sections.filter((s) => s.ink && s.ink.n);
      const ti = S.findIndex((s) => /page-title/.test(s.cls));
      if (ti >= 0 && S[ti + 1]) tgap = Math.round((S[ti + 1].ink.top - S[ti].ink.bottom) * 10) / 10;
      const gaps = []; for (let i = 1; i < S.length; i++) gaps.push(Math.round((S[i].ink.top - S[i - 1].ink.bottom) * 10) / 10);
      res.m = { container: m.container, lefts: top.map((c) => Math.round(c.left * 10) / 10), rights: top.map((c) => Math.round((390 - c.right) * 10) / 10), radii: top.map((c) => Math.round(c.radius * 10) / 10), cards: top.map((c) => c.cls + ' L' + Math.round(c.left * 10) / 10 + ' r' + Math.round(c.radius * 10) / 10 + ' h' + Math.round(c.h)).slice(0, 14), secs: S.map((s) => s.cls + ' [' + Math.round(s.ink.left * 10) / 10 + ']').slice(0, 16), gaps, tgap, textLeft: S.map((s) => Math.round(s.ink.left * 10) / 10) };
    } catch (e) { res.measureErr = e.message.slice(0, 200); }
    res.errors = w.errors().map((x) => x.slice(0, 300));
    res.consoleErr = w.console().filter((x) => x.startsWith('[error]') && !/home-banners|\/profile\/upload|404/.test(x)).slice(0, 8).map((x) => x.slice(0, 240));
    res.text = (await w.text()).slice(0, 700);
  } catch (e) { res.fatal = e.message.slice(0, 300); }
  try { await w.close(); } catch (e) {}
  fs.writeFileSync(path.join(out, P.name + '.json'), JSON.stringify(res, null, 1));
  console.log(P.name, res.route, 'err', (res.errors || []).length, 'cerr', (res.consoleErr || []).length, res.fatal || '', (res.stepErr || []).join(';'));
}
