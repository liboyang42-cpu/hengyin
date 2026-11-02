// node money.mjs <outdir>  — money landing with a non-zero amount on the merchant ledger hero.
// The browser's fetch is wrapped (dummy, in-page only) so /merchant/finance/overview answers personalArrivedThisMonthNet = 1234.56.
// The page's own first-arrival count (pageMotion.countMoney 'lgK' + count.wxs money) then runs; the hero text is sampled every 25 ms.
import fs from 'node:fs';
const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const out = process.argv[2]; fs.mkdirSync(out, { recursive: true });
const w = await launch({ path: 'pages/merchant/index/index', identity: 'e2e-openid-9002', agree: true, settle: 2500 });
await w.eval(`(function(){const of=window.fetch;window.fetch=async function(u,o){const r=await of.apply(this,arguments);const s=String(u&&u.url||u);if(!/finance\\/overview/.test(s))return r;const j=await r.clone().json();if(j&&j.data){j.data.personalArrivedThisMonthNet=1234.56;}return new Response(JSON.stringify(j),{status:r.status,headers:{'content-type':'application/json'}});};})()`);
await w.page.evaluate(() => { window.__samples = []; const t0 = performance.now(); window.__t0 = t0;
  const tick = () => { const p = getCurrentPages().slice(-1)[0]; let txt = null;
    if (p && p.route === 'pages/merchant/ledger/index') { (function walk(r){ for (const el of r.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if ((el.getAttribute('class')||'').includes('fin-hero__amt')) txt = el.textContent.trim(); } })(p.__root); }
    window.__samples.push([Math.round(performance.now() - t0), txt]); if (window.__samples.length < 200) setTimeout(tick, 25); };
  tick(); wx.navigateTo({ url: '/pages/merchant/ledger/index?view=settlement' }); });
const shots = [];
for (const ms of [450, 900, 1300, 2600]) { await new Promise((r) => setTimeout(r, ms - (shots.length ? [450, 900, 1300, 2600][shots.length - 1] : 0))); const f = `${out}/money-${ms}.png`; fs.writeFileSync(f, await w.page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 390, height: 844 } })); shots.push(f); }
await new Promise((r) => setTimeout(r, 1500));
const s = await w.page.evaluate(() => window.__samples);
fs.writeFileSync(`${out}/money-samples.json`, JSON.stringify(s));
const vals = s.filter((x) => x[1]).map(([t, v]) => [t, parseFloat(String(v).replace(/[^0-9.]/g, ''))]);
const first = vals.findIndex((x) => x[1] > 0), last = vals.findIndex((x) => x[1] >= 1234.56);
console.log('errors', w.errors().length, 'first non-zero at', vals[first], 'final at', vals[last], 'final text', s.filter((x) => x[1]).slice(-1)[0]);
if (first >= 0 && last > first) { const t0 = vals[first - 1 >= 0 ? first - 1 : first][0], T = vals[last][0] - t0; for (const q of [0.25, 0.5, 0.75]) { const tt = t0 + T * q; const v = vals.find((x) => x[0] >= tt); console.log('at', q, 'of', T, 'ms ->', v, Math.round(v[1] / 1234.56 * 100) + '%'); } }
await w.close();
