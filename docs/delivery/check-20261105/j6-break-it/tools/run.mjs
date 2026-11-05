// generic: node run.mjs opts.json steps.json ; steps support shot2 names and hook
import fs from 'node:fs'; import { open, sleep } from './drv.mjs';
const opts = JSON.parse(fs.readFileSync(process.argv[2])); const steps = JSON.parse(fs.readFileSync(process.argv[3]));
const w = await open(opts); let n0 = 0;
const nets = async () => { const n = await w.net(); const out = n.slice(n0).map(x => `${x.status} ${x.method} ${x.url.replace(/^.*__api/, '')} ${x.reqBody ? x.reqBody.slice(0, 120) : ''} => ${String(x.resBody || '').slice(0, 220)}`); n0 = n.length; return out; };
for (const s of steps) {
  const [k, v] = Object.entries(s)[0];
  try {
    if (k === 'shot') { console.log('SHOT', v, (await w.route()).join('>')); await w.shot2(v); }
    else if (k === 'hook') await w.eval(fs.readFileSync('/root/j6/hook.js', 'utf8'));
    else if (k === 'net') console.log('NET\n  ' + (await nets()).join('\n  '));
    else if (k === 'text') console.log('TEXT', (await w.text()).slice(0, v || 800));
    else if (k === 'route') console.log('ROUTE', (await w.route()).join('>'));
    else if (k === 'data') console.log('DATA', JSON.stringify(await w.data(v)).slice(0, 1500));
    else if (k === 'eval') console.log('EVAL', JSON.stringify(await w.eval(v)).slice(0, 3000));
    else if (k === 'offline') await w.page.setOfflineMode(!!v);
    else if (k === 'slow') { const c = await w.page.createCDPSession(); await c.send('Network.enable'); await c.send('Network.emulateNetworkConditions', { offline: false, latency: v, downloadThroughput: 20000, uploadThroughput: 20000 }); }
    else if (k === 'wait') await sleep(v);
    else if (k === 'open') await w.open(v);
    else if (k === 'relaunch') await w.relaunch(v);
    else if (k === 'back') await w.back();
    else if (k === 'tap') await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v));
    else if (k === "dbltap") { const pt = await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v)); await w.page.mouse.click(pt.x, pt.y); await w.page.mouse.click(pt.x, pt.y); await sleep(500); }
    else if (k === 'tapSel') await w.tapSel(v);
    else if (k === 'type') await w.type(v[0], v[1]);
    else if (k === 'waitText') await w.waitText(v);
    else if (k === 'scroll') await w.scroll(v);
    else if (k === 'pay') await w.pay(v);
    else if (k === 'modal') await w.modal(v);
    else if (k === 'sheet') await w.sheet(v);
    else if (k === 'login') console.log('LOGIN', JSON.stringify(await w.login(v)));
    else if (k === 'storage') console.log('STORAGE', JSON.stringify(await w.eval('(()=>{const i=wx.getStorageInfoSync();const o={};for(const k of i.keys){o[k]=JSON.stringify(wx.getStorageSync(k)).slice(0,160)}return o})()')));
    else if (k === 'loc') await w.loc(v[0], v[1]);
    else if (k === 'scan') await w.scan(v);
    else if (k === 'sh') console.log('SH', (await import('node:child_process')).execSync(v, { encoding: 'utf8' }).slice(0, 600));
  } catch (e) { console.log('STEPERR', k, JSON.stringify(v), e.message.slice(0, 200)); }
}
console.log('ERRORS', JSON.stringify(w.errors()).slice(0, 2000));
console.log('CONSOLE', JSON.stringify(w.console().filter(x => x.startsWith('[error]') && !/home-banners|favicon|404 \(Not Found|oss-rig|ERR_TUNNEL/.test(x))).slice(0, 2500));
await w.close();
