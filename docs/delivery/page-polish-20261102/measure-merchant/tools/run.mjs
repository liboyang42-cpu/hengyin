// node run.mjs <name> <path> [identity] [preSteps-json] [opts-json]
// Opens a page in the rig (390x844), measures sections and cards in content coordinates, draws red/green labels,
// scrolls through the page and stitches one tall annotated PNG: <out>/<name>.png (+ .plain.png, .json)
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
const HERE = path.dirname(new URL(import.meta.url).pathname);
const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const [name, pth, identity = 'e2e-openid-9002', pre = '[]', extra = '{}'] = process.argv.slice(2);
const OUT = process.env.OUT || path.join(HERE, 'out');
fs.mkdirSync(OUT + '/chunks', { recursive: true });
const MJS = fs.readFileSync(path.join(HERE, 'measure.js'), 'utf8');
const ex = JSON.parse(extra);
const start = ex.start || pth;
const w = await launch({ path: start, identity, agree: true, settle: 3000 });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
if (pth !== start && !ex.start) { await w.open(pth); await sleep(2500); }
for (const s of JSON.parse(pre)) {
  const [k, v] = Object.entries(s)[0];
  if (k === 'tap') await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v)); else if (k === 'wait') await sleep(v); else if (k === 'eval') await w.eval(v); else if (k === 'open') { await w.open(v); await sleep(2500); } else if (k === 'tapSel') await w.tapSel(v); else if (k === 'shot') await w.shot(v);
}
await sleep(ex.settle || 2000);
// optional: inject candidate CSS edits (preview only, nothing in the product changes) into the page or into component shadow roots
for (const c of (ex.css || [])) {
  await w.page.evaluate((c) => {
    const p = getCurrentPages().slice(-1)[0];
    const roots = [];
    if (!c.host) roots.push(p.__root);
    else (function walk(r) { for (const el of r.querySelectorAll('*')) { if (el.shadowRoot) { if (el.tagName.toLowerCase() === c.host) roots.push(el.shadowRoot); walk(el.shadowRoot); } } })(p.__root);
    for (const r of roots) { const s = new CSSStyleSheet(); s.replaceSync(c.css); r.adoptedStyleSheets = [...r.adoptedStyleSheets, s]; }
  }, c);
}
if ((ex.css || []).length) await sleep(800);
await w.page.evaluate(MJS);
await w.page.evaluate(() => window.__scrollTo(0));
await sleep(500);
const m = await w.page.evaluate(() => window.__measure());
m.route = (await w.route()).slice(-1)[0];
m.errors = w.errors();
m.consoleErr = w.console().filter((x) => x.startsWith('[error]')).slice(0, 10);
m.dump = await w.text();
// ---- marks
const mode = (arr) => { const c = {}; for (const v of arr) { const k = Math.round(v); c[k] = (c[k] || 0) + 1; } let best = null, n = 0; for (const k in c) if (c[k] > n) { n = c[k]; best = +k; } return best; };
const near = (a, b) => b == null || Math.abs(a - b) < 2;
const r1 = (x) => Math.round(x * 10) / 10;
const marks = [];
const S = m.sections.filter((s) => s.ink && s.ink.n);
const gaps = [];
for (let i = 1; i < S.length; i++) gaps.push({ i, a: S[i - 1].cls, b: S[i].cls, g: S[i].ink.top - S[i - 1].ink.bottom, y1: S[i - 1].ink.bottom, y2: S[i].ink.top });
// a gap under a heading-like block (bare text, < 40px tall, no surface) is a title gap; others are block gaps
const isHead = (s) => !s.surface && (s.ink.bottom - s.ink.top) < 40 && !/tabs|btn|search|badge/i.test(s.cls);
gaps.forEach((g) => { g.title = isHead(S[g.i - 1]) && !isHead(S[g.i]); });
const gm = mode(gaps.filter((g) => g.g > 0 && !g.title).map((g) => g.g));
const tgm = mode(gaps.filter((g) => g.g > 0 && g.title).map((g) => g.g));
gaps.forEach((g) => { g.ok = near(g.g, g.title ? tgm : gm); if (g.g > 0) marks.push({ kind: 'vgap', x: g.title ? 196 : 4 + (g.i % 2) * 10, y1: g.y1, y2: g.y2, label: (g.title ? 'T ' : '') + r1(g.g), ok: g.ok }); });
const tgs = []; const tm = tgm;
const top = m.cards.filter((c) => !c.nested && c.w < 388);
const lm = mode(top.map((c) => c.left)), rm = mode(top.map((c) => 390 - c.right)), radm = mode(top.map((c) => c.radius));
for (const c of top) {
  const y = c.top + Math.min(c.h / 2, 22);
  c.okL = near(c.left, lm); c.okR = near(390 - c.right, rm); c.okRad = near(c.radius, radm);
  if (c.left > 0.5) marks.push({ kind: 'hgap', x1: 0, x2: c.left, y, label: Math.round(c.left) + '', ok: c.okL });
  if (390 - c.right > 0.5) marks.push({ kind: 'hgap', x1: c.right, x2: 390, y, label: Math.round(390 - c.right) + '', ok: c.okR });
  marks.push({ kind: 'box', x: c.left, y: c.top, w: c.w, h: c.h, r: c.radius, ok: c.okRad });
  marks.push({ kind: 'tag', x: c.left + 3, y: c.top + 3, label: 'r' + Math.round(c.radius) + ' p' + c.pad.map(Math.round).join('/'), ok: c.okRad });
}
const groups = {};
for (const c of m.cards.filter((c) => c.nested)) (groups[c.cls] ||= []).push(c);
for (const arr of Object.values(groups)) {
  const rM = mode(arr.map((c) => c.radius)), pM = mode(arr.map((c) => c.pad[3]));
  for (const c of arr) { c.ok = near(c.radius, rM) && near(c.pad[3], pM); marks.push({ kind: 'box', x: c.left, y: c.top, w: c.w, h: c.h, r: c.radius, ok: c.ok }); }
}
m.summary = { gapMode: gm, titleGapMode: tm, cardLeftMode: lm, cardRightMode: rm, cardRadiusMode: radm };
m.gaps = gaps; m.titleGaps = tgs;
// ---- capture: scroll and stitch
let T = m.cTop; const B = m.bottomBar;
T = await w.page.evaluate((t0) => { let t = t0; const p = getCurrentPages().slice(-1)[0]; (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); const cs = getComputedStyle(el); if (cs.position !== 'fixed' && cs.position !== 'sticky') continue; const r = el.getBoundingClientRect(); if (r.top <= 1 && r.width > 300 && r.height > 20 && r.height < 200 && cs.display !== 'none' && cs.visibility !== 'hidden') t = Math.max(t, r.bottom); } })(p.__root); return t; }, T);
T = Math.max(T, ex.headerMin || 91);
const maxScroll = Math.max(0, m.scrollHeight - m.clientHeight);
const step = Math.max(200, (B - T) - 4);
const chunks = [];
const plain = [];
let s = 0;
for (;;) {
  const real = await w.page.evaluate((y) => window.__scrollTo(y), s);
  await sleep(500);
  await w.page.evaluate((mk, dy) => window.__annotate(mk, dy), marks, real);
  const f = path.join(OUT, 'chunks', `${name}-${chunks.length}.png`);
  fs.writeFileSync(f, await w.page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 390, height: 844 } }));
  await w.page.evaluate(() => window.__annotate(null));
  const fp = f.replace('.png', '-p.png');
  fs.writeFileSync(fp, await w.page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 390, height: 844 } }));
  chunks.push({ file: f, s: real }); plain.push({ file: fp, s: real });
  if (real >= maxScroll - 1 || chunks.length > 12) break;
  s = Math.min(maxScroll, s + step);
}
const spec = { T, B, out: path.join(OUT, name + '.png'), chunks };
execFileSync(process.env.HOME + '/walk-rig/venv/bin/python', [path.join(HERE, 'stitch.py'), JSON.stringify(spec)], { stdio: 'inherit' });
execFileSync(process.env.HOME + '/walk-rig/venv/bin/python', [path.join(HERE, 'stitch.py'), JSON.stringify({ T, B, out: path.join(OUT, name + '.plain.png'), chunks: plain })], { stdio: 'inherit' });
m.capture = { T, B, maxScroll, chunks: chunks.length };
fs.writeFileSync(path.join(OUT, name + '.json'), JSON.stringify(m, null, 1));
console.log(JSON.stringify({ route: m.route, container: m.container, parent: m.parent, parentPad: m.parentStyle.pad, summary: m.summary, T, B, maxScroll, errors: m.errors.length }));
for (const g of gaps) console.log(`  ${g.title ? 'TITLE' : 'GAP  '} ${String(r1(g.g)).padEnd(6)} ${g.ok ? 'ok ' : 'ODD'} ${g.a} -> ${g.b}`);
for (const t of tgs) console.log(`  TITLE ${r1(t.gap)}  ${t.head} -> ${t.next}`);
for (const c of m.cards) console.log(`  ${c.nested ? '   ' : 'CARD'} ${(c.owner + ':' + c.cls).slice(0, 58).padEnd(58)} y=${Math.round(c.top)} w=${Math.round(c.w)} L=${r1(c.left)} R=${r1(390 - c.right)} r=${c.radii.join('/')} pad=${c.pad.join('/')} | ${c.text.slice(0, 16)}`);
await w.close();
