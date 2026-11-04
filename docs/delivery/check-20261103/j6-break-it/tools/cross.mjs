// client-side id swap: a member opens pages scoped to someone else's club/merchant/order
import fs from 'node:fs'; import { open, sleep } from './drv.mjs';
const cases = JSON.parse(fs.readFileSync(process.argv[2])); const out = [];
for (const [n, ident, url] of cases) {
  const w = await open({ path: url, identity: ident, agree: true });
  await sleep(3500);
  const net = (await w.net()).filter(x => !/config|common|compliance|banner/.test(x.url)).map(x => `${x.status} ${x.url.replace(/^.*__api/, '').slice(0, 90)} => ${String(x.resBody).slice(0, 140)}`);
  const text = (await w.text()).slice(0, 400);
  await w.shot2(n);
  out.push({ n, ident, url, route: (await w.route()).join('>'), text, net, errs: w.errors() });
  console.log('==', n, ident, url, '\n route', out.at(-1).route, '\n text', text, '\n net', net.slice(0, 8).join('\n     '), '\n errs', w.errors().join(' ').slice(0, 300));
  await w.close();
}
fs.writeFileSync(process.argv[3], JSON.stringify(out, null, 1));
