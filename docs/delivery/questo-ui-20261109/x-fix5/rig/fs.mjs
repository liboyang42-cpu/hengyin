// node fs.mjs <kind> <prefix> <mode: full|flow>  full:用 setData 直接开整屏玩法(带 look);其余步骤同 play.mjs
const { launch } = await import(process.env.RIG_WALK);
import { execFileSync } from 'node:child_process';
const [kind, prefix, extra] = process.argv.slice(2);
const data = execFileSync('node', [process.env.GEN, process.env.XCX, kind]).toString();
const w = await launch({ path: 'pages/play/index?preview=1', identity: 'e2e-openid-9004', settle: 2500, agree: true });
await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p.applyPreviewData(${data}); })()`);
await new Promise(r => setTimeout(r, 2500));
for (const s of JSON.parse(extra || '[]')) {
  if (s.shot) await w.shot(prefix + s.shot);
  else if (s.tap) await w.tap(s.tap);
  else if (s.tryTap) { try { await w.tap(s.tryTap); } catch (e) { console.log('skip tap', s.tryTap); } }
  else if (s.tapSel) { try { await w.tapSel(s.tapSel); } catch (e) { console.log('skip sel', s.tapSel); } }
  else if (s.wait) await new Promise(r => setTimeout(r, s.wait));
  else if (s.eval) console.log('EVAL', JSON.stringify(await w.eval(s.eval)).slice(0, 300));
  else if (s.scroll != null) await w.scroll(s.scroll);
  else if (s.dump) console.log(await w.text());
}
console.log('ERR', w.errors().slice(0, 5));
await w.browser.close();
