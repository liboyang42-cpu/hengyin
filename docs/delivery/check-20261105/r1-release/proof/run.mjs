// r1 driver: rig walk.mjs library + dialog clearing + API body-code capture. Usage: node run.mjs opts.json steps.json
import fs from 'fs';
import { launch } from '/root/rigsrc/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const opts = JSON.parse(fs.readFileSync(process.argv[2], 'utf8')), steps = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const w = await launch(opts);
async function clear() {
  for (let i = 0; i < 4; i++) {
    const t = await w.text();
    if (t.includes('同意并继续') && t.includes('隐私保护')) { await w.tap('我已阅读并同意'); await sleep(400); await w.tap('同意并继续'); await sleep(2000); continue; }
    if (t.includes('暂不定位')) { await w.tap('暂不定位'); await sleep(1500); continue; }
    break;
  }
}
for (const s of steps) {
  const [k, v] = Object.entries(s)[0];
  try {
    if (k === 'clear') await clear();
    else if (k === 'tap') await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v));
    else if (k === 'tapSel') await w.tapSel(v);
    else if (k === 'open') await w.open(v);
    else if (k === 'relaunch') await w.relaunch(v);
    else if (k === 'back') await w.back();
    else if (k === 'wait') await sleep(v);
    else if (k === 'waitText') await w.waitText(v);
    else if (k === 'scroll') await w.scroll(v);
    else if (k === 'pay') await w.pay(v);
    else if (k === 'modal') await w.modal(v);
    else if (k === 'sheet') await w.sheet(v);
    else if (k === 'loc') await w.loc(v[0], v[1]);
    else if (k === 'type') await w.type(v[0], v[1]);
    else if (k === 'shot') console.log('shot', await w.shot(v));
    else if (k === 'eval') console.log('eval', JSON.stringify(await w.eval(v)).slice(0, 1500));
    else if (k === 'data') console.log('data', JSON.stringify(await w.data(v)).slice(0, 1500));
    else if (k === 'dump') console.log('text:', (await w.text()).slice(0, 900));
    else if (k === 'route') console.log('route', JSON.stringify(await w.route()));
    console.log('ok  ', k, typeof v === 'string' ? v.slice(0, 60) : JSON.stringify(v).slice(0, 60));
  } catch (e) { console.log('FAIL', k, JSON.stringify(v).slice(0, 60), '-', String(e.message || e).slice(0, 200)); }
}
console.log('route', JSON.stringify(await w.route()));
const net = await w.net(); console.log('--- network', net.length, 'non-2xx:', JSON.stringify(net.filter(x => x.status >= 300 || x.status === 0).map(x => [x.status, String(x.url).replace(/^.*__api/, '')])));
console.log('--- api body errors', JSON.stringify(w.console().filter(l => l.startsWith('[api]'))));
console.log('--- page errors', JSON.stringify(w.errors()));
console.log('--- console errors (excluding 404 images/favicon)', JSON.stringify(w.console().filter(l => /^\[error\]/.test(l) && !/404|Failed to load resource/.test(l)).slice(0, 10)));
await w.close();
