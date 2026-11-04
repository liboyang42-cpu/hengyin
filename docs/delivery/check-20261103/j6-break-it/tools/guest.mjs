// Guest sweep: every registered page opened with reLaunch as a fresh guest (privacy agreed once).
import fs from 'node:fs'; import { open, sleep } from './drv.mjs';
const pages = JSON.parse(fs.readFileSync(process.env.HOME + '/j6/pages.json'));
const out = []; const start = +(process.argv[2] || 0), end = +(process.argv[3] || pages.length);
let w = await open({ path: 'pages/index/index', agree: true });
for (let i = start; i < end; i++) {
  const p = pages[i];
  const e0 = w.errors().length, c0 = w.console().length, n0 = (await w.net()).length;
  try { await w.relaunch('/' + p); } catch (e) {}
  await sleep(2200);
  const route = (await w.route()).join('>');
  const text = await w.text();
  const net = (await w.net()).slice(n0).map(x => `${x.status || x.statusCode || ''} ${x.method || ''} ${(x.url || '').replace(/^.*\/__api/, '')}`);
  const errs = w.errors().slice(e0); const cons = w.console().slice(c0).filter(x => x.startsWith('[error]'));
  const shot = String(i).padStart(3, '0') + '-guest-' + p.replace(/\//g, '_');
  await w.shot2('guest/' + shot);
  out.push({ i, p, route, text: text.slice(0, 600), net, errs, cons });
  fs.writeFileSync(process.env.HOME + `/j6/guest-${start}.json`, JSON.stringify(out, null, 1));
  if (errs.length > 3 || i % 40 === 39) { await w.close(); w = await open({ path: 'pages/index/index', agree: true }); }
}
await w.close();
