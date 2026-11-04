// Usage: node sweep.mjs '<json>'  {id, path(with query), identity, pre:[steps], max, skip:[regex], scrolls}
// Opens the real page, shoots it, lists every visible control with a tap binding, taps each one in turn and records
// what happened (route change, modal, action sheet, new text, runtime "no handler", page errors). Restores state
// after each tap (cancel modal/sheet, navigateBack when the route grew).
import fs from 'node:fs';
import path from 'node:path';
import { open, sleep } from './lib.mjs';
const cfg = JSON.parse(process.argv[2]);
const LOG = path.resolve(new URL('..', import.meta.url).pathname, 'logs');
fs.mkdirSync(LOG, { recursive: true });
const out = { id: cfg.id, path: cfg.path, identity: cfg.identity || 'guest', controls: [] };
const w = await open({ path: cfg.path.split('?')[0], identity: cfg.identity });
// open with query via reLaunch/navigate so options reach onLoad
if (cfg.path.includes('?') || cfg.via) { await w.relaunch('/' + (cfg.via || 'pages/index/index')); await sleep(1500); await w.open('/' + cfg.path); }
await sleep(cfg.settle || 3000);
for (const s of cfg.pre || []) { const [k, v] = Object.entries(s)[0]; try { if (k === 'tap') await w.tap(v); else if (k === 'wait') await sleep(v); else if (k === 'eval') await w.eval(v); else if (k === 'scroll') await w.scroll(v); else if (k === 'modal') await w.modal(v); } catch (e) { out.preErr = (out.preErr || '') + e.message + ';'; } }
async function reopen() { await w.relaunch('/' + (cfg.via || 'pages/index/index')); await sleep(1500); await w.open('/' + cfg.path); await sleep(cfg.settle || 3000); for (const s of cfg.pre || []) { const [k, v] = Object.entries(s)[0]; try { if (k === 'tap') await w.tap(v); else if (k === 'wait') await sleep(v); else if (k === 'eval') await w.eval(v); else if (k === 'modal') await w.modal(v); } catch (e) {} } }
const baseRoute = (await w.route()).slice(-1)[0];
out.route = await w.route();
out.text = await w.text();
await w.shot2(cfg.id + '-a-open');
let shotN = 0;
for (let i = 1; i <= (cfg.scrolls ?? 2); i++) { await w.scroll(700); await sleep(400); const t = await w.text(); if (t !== out.text) { out['text_s' + i] = t; await w.shot2(cfg.id + '-a-scroll' + i); shotN++; } }
await w.scroll(-5000); await sleep(300); out.text = await w.text();
const listCtl = () => w.page.evaluate(() => {
  const res = []; const seen = new Set();
  (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); const on = el.__on; if (!on || !(on.tap || on.longpress)) continue; const r = el.getBoundingClientRect(); if (r.width < 2 || r.height < 2) continue; const cs = getComputedStyle(el); if (cs.visibility === 'hidden' || cs.display === 'none') continue;
    let txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30); if (!txt && el.shadowRoot) txt = (el.shadowRoot.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30);
    const b = on.tap || on.longpress; const h = typeof b.h === 'string' ? b.h : '(fn)'; const key = h + '|' + txt + '|' + (el.__ds ? JSON.stringify(el.__ds).slice(0, 60) : '');
    if (seen.has(key)) continue; seen.add(key); res.push({ key, h, txt, w: Math.round(r.width), hgt: Math.round(r.height), y: Math.round(r.top + (document.scrollingElement.scrollTop || 0)) }); } })(document);
  return res; });
const ctls = await listCtl();
out.controlCount = ctls.length;
const skip = (cfg.skip || []).map((s) => new RegExp(s));
let n = 0;
for (const c of ctls.slice(0, cfg.max || 40)) {
  if (/^(onBack|goBack|handleBack|back|onNavBack|navBack|onTapBack)\|/i.test(c.key)) { out.controls.push({ ...c, result: 'back (not tapped)' }); continue; }
  if (skip.some((r) => r.test(c.key))) { out.controls.push({ ...c, result: 'SKIPPED (destructive / writes; walked by hand)' }); continue; }
  const before = await w.text(); const log0 = (await w.log()).length; const err0 = w.errors().length;
  const ok = await w.page.evaluate((key) => {
    let hit = null; (function walk(root) { for (const el of root.querySelectorAll('*')) { if (hit) return; if (el.shadowRoot) walk(el.shadowRoot); const on = el.__on; if (!on || !(on.tap || on.longpress)) continue; const b = on.tap || on.longpress; const h = typeof b.h === 'string' ? b.h : '(fn)'; let txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30); if (!txt && el.shadowRoot) txt = (el.shadowRoot.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30); if (h + '|' + txt + '|' + (el.__ds ? JSON.stringify(el.__ds).slice(0, 60) : '') === key) hit = el; } })(document);
    if (!hit) return false; hit.scrollIntoView({ block: 'center' }); const r = hit.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }, c.key);
  if (!ok) { out.controls.push({ ...c, result: 'not reachable after earlier taps' }); continue; }
  await sleep(250);
  // click at the element centre (real hit-testing, so overlays that swallow taps show up as no-ops)
  const pt = await w.page.evaluate((key) => null, c.key);
  await w.page.evaluate((key) => { let hit = null; (function walk(root) { for (const el of root.querySelectorAll('*')) { if (hit) return; if (el.shadowRoot) walk(el.shadowRoot); const on = el.__on; if (!on || !(on.tap || on.longpress)) continue; const b = on.tap || on.longpress; const h = typeof b.h === 'string' ? b.h : '(fn)'; let txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30); if (!txt && el.shadowRoot) txt = (el.shadowRoot.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30); if (h + '|' + txt + '|' + (el.__ds ? JSON.stringify(el.__ds).slice(0, 60) : '') === key) hit = el; } })(document); window.__tapTarget = hit; }, c.key);
  const box = await w.page.evaluate(() => { const r = window.__tapTarget.getBoundingClientRect(); return { x: r.left + Math.min(r.width / 2, r.width - 2), y: r.top + Math.min(r.height / 2, r.height - 2) }; });
  if (box.y < 0 || box.y > 844) { out.controls.push({ ...c, result: 'off-screen' }); continue; }
  await w.page.mouse.click(box.x, box.y); await sleep(1600);
  const r = { ...c };
  const route = await w.route(); r.route = route.slice(-1)[0];
  const sys = await w.page.evaluate(() => { const m = document.querySelector('.wx-modal'); const s = document.querySelector('.wx-as'); const t = document.querySelector('.wx-toast'); return { modal: m ? m.innerText.replace(/\s+/g, ' ').slice(0, 140) : '', sheet: s ? s.innerText.replace(/\s+/g, ' ').slice(0, 140) : '', toast: t ? t.innerText.replace(/\s+/g, ' ').slice(0, 80) : '' }; });
  Object.assign(r, Object.fromEntries(Object.entries(sys).filter(([, v]) => v)));
  const after = await w.text(); const bset = new Set(before.split(' | ')); const added = after.split(' | ').filter((x) => !bset.has(x));
  if (added.length) r.newText = added.slice(0, 12).join(' | ').slice(0, 300);
  const logs = (await w.log()).slice(log0); if (logs.length) r.runtime = logs.slice(0, 3).join(' ; ').slice(0, 200);
  const errs = w.errors().slice(err0); if (errs.length) r.pageError = errs[0].split('\n')[0].slice(0, 200);
  if (r.route !== baseRoute || r.modal || r.sheet || (r.newText && r.newText.length > 40)) { n++; if (n <= (cfg.maxShots || 6)) { const nm = cfg.id + '-t' + String(n).padStart(2, '0'); await w.shot2(nm); r.shot = nm; } }
  // restore: cancel system modal/sheet, then reopen the page fresh if anything still differs from the opened state
  if (sys.modal) { try { await w.modal('cancel'); } catch (e) { try { await w.modal('confirm'); } catch (_) {} } }
  if (sys.sheet) { await w.page.evaluate(() => { const c = document.querySelector('.wx-as__c'); if (c) c.click(); }); await sleep(400); }
  const rt = await w.route();
  if (rt.join('>') !== out.route.join('>') || (await w.text()) !== out.text) { await reopen(); if ((await w.route()).slice(-1)[0] !== baseRoute) { r.note = 'could not reopen'; out.controls.push(r); break; } }
  r.result = r.route !== baseRoute ? 'navigates' : (r.modal ? 'modal' : r.sheet ? 'sheet' : r.toast ? 'toast' : r.newText ? 'ui change' : r.runtime ? 'runtime-log' : 'NO VISIBLE EFFECT');
  out.controls.push(r);
}
out.report = await w.report();
fs.writeFileSync(path.join(LOG, cfg.id + '.json'), JSON.stringify(out, null, 1));
console.log(cfg.id, 'route', out.route.join('>'), 'controls', out.controlCount, 'errors', out.report.pageErrors.length, 'badNet', out.report.badNet.length);
for (const c of out.controls) console.log(' ', (c.result || '').padEnd(16), c.h.padEnd(26), JSON.stringify(c.txt).slice(0, 32).padEnd(32), c.route && c.route !== baseRoute ? '-> ' + c.route : '', c.modal ? 'M:' + c.modal.slice(0, 70) : '', c.toast ? 'T:' + c.toast : '', c.runtime ? 'R:' + c.runtime.slice(0, 80) : '', c.pageError ? 'E:' + c.pageError.slice(0, 90) : '');
console.log('TEXT:', out.text.slice(0, 900));
if (out.report.pageErrors.length) console.log('PAGEERR', out.report.pageErrors.slice(0, 3));
if (out.report.badNet.length) console.log('BADNET', out.report.badNet.slice(0, 6));
await w.close();
