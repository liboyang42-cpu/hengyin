// f-play 走查驱动: node drive.mjs <opts-json> <steps-json|@file>
// steps: open/tap/tryTap/tapSel/wait/shot/strip/eval/dump/scroll/loc/shake/modal/data
const { launch } = await import(process.env.RIG_WALK || '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const opts = JSON.parse(process.argv[2]);
const raw = process.argv[3] || '[]';
const steps = JSON.parse(raw.startsWith('@') ? fs.readFileSync(raw.slice(1), 'utf8') : raw);
const w = await launch(Object.assign({ settle: 3000, agree: true }, opts));
if (opts.previewKind) {   // 游玩页自己的试玩入口(?preview=1 + applyPreviewData),数据由 gen.js 生成
  const data = execFileSync('node', [new URL('./gen.js', import.meta.url).pathname, process.env.XCX || '/home/user/hengyin/chengyinhub-xcx', opts.previewKind]).toString();
  await w.eval(`(() => { getCurrentPages().slice(-1)[0].applyPreviewData(${data}); })()`);
  await new Promise(r => setTimeout(r, 2500));
}
const P = 'getCurrentPages().slice(-1)[0]';
for (const s of steps) {
  try {
    if (s.shot) { await w.shot(s.shot); console.log('shot', s.shot); }
    else if (s.strip) { for (let i = 0; i < (s.n || 4); i++) { await w.shot(s.strip + '-f' + (i + 1)); await new Promise(r => setTimeout(r, s.every || 400)); } console.log('strip', s.strip); }
    else if (s.tap) { await w.tap(...[].concat(s.tap)); console.log('tap', JSON.stringify(s.tap)); }
    else if (s.tryTap) { try { await w.tap(...[].concat(s.tryTap)); console.log('tap', JSON.stringify(s.tryTap)); } catch (e) { console.log('skip', JSON.stringify(s.tryTap)); } }
    else if (s.tapSel) { await w.tapSel(s.tapSel); console.log('tapSel', s.tapSel); }
    else if (s.wait) await new Promise(r => setTimeout(r, s.wait));
    else if (s.eval) console.log('EVAL', JSON.stringify(await w.eval(s.eval.replace(/\$P/g, P))).slice(0, s.max || 1500));
    else if (s.scroll != null) await w.scroll(s.scroll);
    else if (s.loc) await w.loc(s.loc[0], s.loc[1]);
    else if (s.shake) console.log('SHAKE', await w.shake(s.shake, s.gap));
    else if (s.open) { await w.open(s.open); console.log('open', s.open); }
    else if (s.relaunch) { await w.relaunch(s.relaunch); console.log('relaunch', s.relaunch); }
    else if (s.modal) { await w.modal(s.modal); console.log('modal', s.modal); }
    else if (s.scan) { await w.scan(s.scan); console.log('scan set'); }
    else if (s.type) { await w.type(s.type[0], s.type[1]); console.log('type', s.type[1]); }
    else if (s.route) console.log('ROUTE', await w.eval(P + '.route'));
    else if (s.dump) console.log('TEXT', (await w.text()).replace(/\s+/g, ' ').slice(0, s.max || 1500));
  } catch (e) { console.log('FAIL', JSON.stringify(s).slice(0, 100), e.message.slice(0, 200)); }
}
console.log('ERR', JSON.stringify(w.errors().slice(0, 6)).slice(0, 1500));
console.log('CONERR', JSON.stringify(w.console().filter(l => l.startsWith('[error]')).slice(0, 6)).slice(0, 1200));
if (process.env.CONGREP) console.log('CONGREP', JSON.stringify(w.console().filter(l => l.includes(process.env.CONGREP)).slice(0, 8)));
if (process.env.NET) console.log('NET', JSON.stringify((await w.net()).filter(n => new RegExp(process.env.NET).test(n.url)).map(n => [n.method, n.url.slice(0, 110), n.status, (n.resBody || '').slice(0, 260)])));
await w.browser.close();
