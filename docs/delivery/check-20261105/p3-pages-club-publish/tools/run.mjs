// Generic second-pass walker: node run.mjs '<opts json>' '<steps json>'
// opts: {identity, path (start page, default home), open (navigateTo url after start)}
// steps: {tap:text, x:true(exact), nth}, {tapSel:css}, {fill:[values]} (fills empty visible inputs/textareas in order),
//   {type:[css,text]}, {wait:ms}, {shot:name}, {dump:label}, {modal:'confirm'|'cancel'}, {sheet:idx|text},
//   {open:url}, {relaunch:url}, {back:1}, {scroll:px}, {data:'expr'}, {eval:'js'}, {pay:'success'}, {scan:payload}, {loc:[lat,lng]}
import { open, sleep } from './lib.mjs';
const o = JSON.parse(process.argv[2]);
const steps = JSON.parse(process.argv[3] || '[]');
const w = await open({ path: o.path || 'pages/index/index', identity: o.identity, lat: o.lat, lng: o.lng });
if (o.open) { await w.open(o.open); await sleep(o.settle || 3000); }
const dump = async (k) => console.log('##', k, '|', (await w.text()).replace(/9:41 \| ▮▮▮ ◔ ▭ \| ··· ◉/g, '').slice(0, o.dumpLen || 900));
for (const s of steps) {
  try {
    if (s.tap !== undefined) { await w.tap(s.tap, { exact: !!s.x, nth: s.nth || 0 }); }
    if (s.tapSel) await w.tapSel(s.tapSel);
    if (s.fill) { const all = [...await w.page.$$('pierce/input'), ...await w.page.$$('pierce/textarea')]; let i = 0; for (const h of all) { if (!(await h.boundingBox())) continue; if (await h.evaluate((e) => e.value)) continue; if (i >= s.fill.length) break; await h.click(); await h.type(String(s.fill[i++]), { delay: 8 }); } }
    if (s.type) await w.type(s.type[0], s.type[1]);
    if (s.modal) await w.modal(s.modal);
    if (s.sheet !== undefined) await w.sheet(s.sheet);
    if (s.open) await w.open(s.open);
    if (s.relaunch) await w.relaunch(s.relaunch);
    if (s.back) await w.back();
    if (s.scroll) await w.scroll(s.scroll);
    if (s.pay) await w.pay(s.pay);
    if (s.scan) await w.scan(s.scan);
    if (s.loc) await w.loc(s.loc[0], s.loc[1]);
    if (s.data) console.log('data', s.data, JSON.stringify(await w.data(s.data)).slice(0, 1500));
    if (s.eval) console.log('eval', JSON.stringify(await w.eval(s.eval)).slice(0, 1500));
  } catch (e) { console.log('STEP ERR', JSON.stringify(s).slice(0, 120), e.message.slice(0, 160)); }
  await sleep(s.wait || 900);
  if (s.shot) { await w.shot2(s.shot); }
  if (s.dump || s.shot) await dump(s.dump || s.shot);
}
console.log('route', (await w.route()).join('>'));
const rep = await w.report();
console.log('REPORT', JSON.stringify({ ...rep, consoleErrors: rep.consoleErrors.filter((c) => !/404/.test(c)) }));
const net = (await w.net()).filter((n) => /\/api\//.test(n.url) && n.method !== 'GET' && !/role\/info|access\/me|login|track|consent/.test(n.url));
if (o.net) for (const n of net) console.log('NET', n.status, n.method, n.url.replace(/.*__api/, ''), String(JSON.stringify(n.body)).slice(0, 300));
await w.close();
