// re-run listed page indices, fresh guest browser per page (no carry-over from a silent login)
import fs from 'node:fs'; import { open, sleep } from './drv.mjs';
const pages = JSON.parse(fs.readFileSync('/root/j6/pages.json'));
const idx = JSON.parse(process.argv[2]); const out = [];
for (const i of idx) {
  const p = pages[i];
  const w = await open({ path: 'pages/index/index', agree: true });
  const n0 = (await w.net()).length, e0 = w.errors().length, c0 = w.console().length;
  try { await w.relaunch('/' + p); } catch (e) {}
  await sleep(2500);
  const net = (await w.net()).slice(n0).map(x => `${x.status} ${x.method} ${x.url.replace(/^.*\/__api/, '')}`);
  out.push({ i, p, route: (await w.route()).join('>'), text: (await w.text()).slice(0, 600), net, errs: w.errors().slice(e0), cons: w.console().slice(c0).filter(x => x.startsWith('[error]')) });
  await w.shot2('guest/' + String(i).padStart(3, '0') + '-guest-' + p.replace(/\//g, '_'));
  fs.writeFileSync(process.argv[3], JSON.stringify(out, null, 1));
  await w.close();
}
