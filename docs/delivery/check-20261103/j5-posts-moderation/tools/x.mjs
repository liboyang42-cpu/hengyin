// generic explore: OPTS json, STEPS: array of [kind, arg]
import { start, sleep } from './lib.mjs';
const o = JSON.parse(process.env.OPTS); const steps = JSON.parse(process.env.STEPS);
const w = await start(o);
for (const [k, v, v2] of steps) {
  if (k === 'tap') await w.step('tap ' + v, () => w.tap(v, v2 || {}));
  else if (k === 'tapSel') await w.step('tapSel ' + v, () => w.tapSel(v));
  else if (k === 'type') await w.step('type ' + v, () => w.typeInto(v, v2));
  else if (k === 'wait') await sleep(v);
  else if (k === 'shot') await w.snap(v);
  else if (k === 'dump') await w.dump();
  else if (k === 'early') console.log('early', (await w.text()).slice(0, 300));
  else if (k === 'modal') await w.step('modal ' + v, () => w.modal(v));
  else if (k === 'sheet') await w.step('sheet ' + v, () => w.sheet(v));
  else if (k === 'open') await w.step('open ' + v, () => w.open(v));
  else if (k === 'back') await w.back();
  else if (k === 'scroll') await w.scroll(v);
  else if (k === 'photo') await w.photo(v);
  else if (k === 'eval') console.log('eval', JSON.stringify(await w.eval(v)).slice(0, 800));
  else if (k === 'resp') w.lastBody(new RegExp(v));
  else if (k === 'xy') { await w.page.mouse.click(v, v2); await sleep(900); }
}
await w.finish();
