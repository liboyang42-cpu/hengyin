// Hand walk: 创建俱乐部 as 9009 (now 主理人), every step shot; prints visible text per step.
import { open, sleep } from './lib.mjs';
const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9009' });
const say = async (k) => console.log('##', k, '|', (await w.text()).slice(0, 700));
const fill = async (vals) => { const all = [...await w.page.$$('pierce/input'), ...await w.page.$$('pierce/textarea')]; let i = 0; for (const h of all) { if (!(await h.boundingBox())) continue; if (await h.evaluate((e) => e.value)) continue; if (i >= vals.length) break; await h.click(); await h.type(vals[i++], { delay: 5 }); } await sleep(500); };
await w.open('/pages/club/create/index'); await sleep(2500); await w.shot2('h12-create-intro');
await w.tap('开始创建'); await sleep(1500); await w.shot2('h13-create-step1'); await say('s1');
const steps = JSON.parse(process.argv[2] || '[]');
let n = 14;
for (const st of steps) {
  if (st.tap) { const ok = await w.safeTap(st.tap, st.opt || {}); console.log('tap', st.tap, ok); }
  if (st.fill) await fill(st.fill);
  if (st.eval) console.log('eval', await w.eval(st.eval));
  if (st.modal) { try { await w.modal(st.modal); } catch (e) { console.log('no modal'); } }
  if (st.sheet !== undefined) { try { await w.sheet(st.sheet); } catch (e) { console.log('no sheet'); } }
  await sleep(st.wait || 1200);
  if (st.shot) { await w.shot2('h' + (n++) + '-create-' + st.shot); await say(st.shot); }
}
console.log('route', (await w.route()).join('>'));
console.log(JSON.stringify(await w.report()));
await w.close();
