// node play.mjs <kind> <prefix> [steps-json]
const { launch } = await import(process.env.RIG_WALK); // $RIG_WALK = <rig worktree>/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs
import { execFileSync } from 'node:child_process';
const [kind, prefix, extra] = process.argv.slice(2);
const data = execFileSync('node', [process.env.GEN, process.env.XCX, kind]).toString();
const w = await launch({ path: 'pages/play/index?preview=1', identity: 'e2e-openid-9004', settle: 2500, agree: true });
await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p.applyPreviewData(${data}); })()`);
await new Promise(r => setTimeout(r, 2500));
const steps = JSON.parse(extra || '[]');
for (const s of steps) { console.log('STEP', JSON.stringify(s).slice(0,60));
  if (s.shot) await w.shot(prefix + s.shot);
  else if (s.tap) await w.tap(s.tap);
  else if (s.tryTap) { try { await w.tap(s.tryTap); } catch (e) { console.log('skip tap', s.tryTap); } }
  else if (s.tapSel) await w.tapSel(s.tapSel);
  else if (s.wait) await new Promise(r => setTimeout(r, s.wait));
  else if (s.eval) console.log('EVAL', JSON.stringify(await w.eval(s.eval)));
  else if (s.scroll != null) await w.scroll(s.scroll);
  else if (s.dump) console.log((await w.text()));
}
console.log('ERR', w.errors().slice(0, 5));
console.log('CON', w.console().filter(l => l.startsWith('[error]')).slice(0, 8));
await w.browser.close();
