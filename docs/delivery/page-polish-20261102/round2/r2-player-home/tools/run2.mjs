import fs from 'node:fs';
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const [, , name, optsJ, stepsJ] = process.argv;
const opts = JSON.parse(optsJ); const steps = JSON.parse(stepsJ || '[]');
const OUT = 'out/' + name; fs.mkdirSync(OUT, { recursive: true });
const w = await launch({ identity: 'e2e-openid-9004', agree: true, ...opts });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await sleep(2500);
for (let round=0; round<3; round++) {
// dismiss product modal if showing
try { const has = await w.eval(`(()=>{let f=false;(function walk(r){for(const e of r.querySelectorAll('*')){if(e.shadowRoot)walk(e.shadowRoot);if(/\\bmo__card\\b/.test(e.className)&&e.getBoundingClientRect().width>0)f=true;}})(document);return f})()`); if (has && !opts.keepModal) { await w.tapSel('.mo__btn--cancel').catch(() => w.tapSel('.mo__close')); await sleep(800); console.log('dismissed modal'); } } catch (e) { console.log('modal?', e.message); }
await sleep(1200);
for (let k=0;k<2;k++){ try { if (await w.eval(`(()=>{let f=false;(function walk(r){for(const e of r.querySelectorAll('*')){if(e.shadowRoot)walk(e.shadowRoot);for(const n of e.childNodes)if(n.nodeType===3&&n.data.includes('同意并继续')&&e.getBoundingClientRect().top<844&&e.getBoundingClientRect().width>0)f=true;}})(document);return f})()`)) { await w.tap('我已阅读并同意'); await w.tap('同意并继续'); await sleep(1500); console.log('privacy agreed'); } } catch(e){ console.log('privacy?', e.message);} }
}
await sleep(4000);
for (const s of steps) { const [k, v] = Object.entries(s)[0]; try {
  if (k === 'tap') await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v)); else if (k === 'open') await w.open(v); else if (k === 'wait') await sleep(v);
  else if (k === 'eval') console.log('eval', JSON.stringify(await w.eval(v))); else if (k === 'tapSel') await w.tapSel(v); else if (k === 'scroll') await w.scroll(v); else if (k === 'modal') await w.modal(v);
  console.log('ok', k, JSON.stringify(v)); } catch (e) { console.log('FAIL', k, v, e.message); } }
await sleep(3000);
await w.eval(`(()=>{function inj(r){const t=document.createElement('style');t.textContent='wx-scroll-view{position:relative}';(r.head||r).appendChild(t);for(const e of r.querySelectorAll('*'))if(e.shadowRoot)inj(e.shadowRoot);}inj(document);})()`);
await sleep(400);
const m = await w.eval(fs.readFileSync('measure.js', 'utf8'));
m.route = await w.route(); m.text = (await w.text()).slice(0, 3000);
m.errors = w.errors().map((e) => e.slice(0, 300));
// screens
const band = await w.eval(`(()=>{const P=getCurrentPages().slice(-1)[0];let sc=null,area=0;(function walk(root){for(const el of root.querySelectorAll('*')){if(el.shadowRoot)walk(el.shadowRoot);if(el.scrollHeight>el.clientHeight+4&&/auto|scroll/.test(getComputedStyle(el).overflowY)){const r=el.getBoundingClientRect();const a=r.width*Math.min(r.height,844);if(a>area&&r.height>200&&r.width>300){area=a;sc=el;}}}})(P.__root);if(!sc||P.__host.scrollHeight>P.__host.clientHeight+4&&area<390*400)sc=P.__host;window.__sc=sc;const R=sc.getBoundingClientRect();let top=Math.max(0,R.top),bot=Math.min(844,R.bottom);
const inS=(el)=>{let n=el;while(n){if(n===sc)return true;n=n.parentNode||n.host;}return false;};
(function walk(root){for(const el of root.querySelectorAll('*')){if(el.shadowRoot)walk(el.shadowRoot);const cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='sticky'&&cs.position!=='absolute')continue;if(inS(el)&&cs.position!=='sticky')continue;const r=el.getBoundingClientRect();if(r.width<300||r.height<20||r.height>400||cs.visibility==='hidden'||+cs.opacity===0)continue;if(r.top<=top+2&&r.bottom<320&&r.bottom>top)top=r.bottom;if(r.bottom>=bot-2&&r.top>560&&r.top<bot)bot=r.top;}})(document);
return {top:Math.round(top),bot:Math.round(bot),sh:sc.scrollHeight,ch:sc.clientHeight,st:sc.scrollTop};})()`);
m.band = band; const screens = [];
let S0 = band.st; let idx = 0;
fs.writeFileSync(`${OUT}/s0.png`, await w.page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 390, height: 844 } }));
screens.push({ file: 's0.png', S: 0 });
const bandH = band.bot - band.top;
let maxScreens = 6;
while (bandH > 100 && idx < maxScreens) {
  const st = await w.eval(`(()=>{window.__sc.scrollTop+=${bandH};return window.__sc.scrollTop})()`); await sleep(500);
  const S = st - S0; if (S <= (screens.at(-1).S)) break;
  idx++; fs.writeFileSync(`${OUT}/s${idx}.png`, await w.page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 390, height: 844 } }));
  screens.push({ file: `s${idx}.png`, S });
}
m.screens = screens;
fs.writeFileSync(`${OUT}/m.json`, JSON.stringify(m, null, 1));
console.log('route', m.route, 'scroller', m.scroller, 'scH', m.scH, 'band', JSON.stringify(band), 'screens', screens.length, 'errors', m.errors.length);
await w.close();
