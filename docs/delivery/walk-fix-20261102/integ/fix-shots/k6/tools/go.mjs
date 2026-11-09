// p2 second-pass helper: node go.mjs <script.mjs>  where script exports default async (ctx)
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import fs from 'node:fs';
const OUT = '/home/user/hengyin/docs/delivery/walk-fix-20261102/integ/fix-shots/k6';
process.env.RIG_SHOTS = OUT;
const LOG = '/root/k6tools/logs'; fs.mkdirSync(LOG, { recursive: true });
export async function go(name, opts, fn) {
  const w = await launch({ agree: true, ...opts });
  const rec = { name, opts, steps: [] };
  const shot = async (n) => { await w.shot(n); const t = await w.text(); rec.steps.push({ shot: n, route: (await w.route()).slice(-1)[0], text: t.slice(0, 3000) }); console.log(`== ${n} [${(await w.route()).slice(-1)[0]}]\n${t.slice(0, 1200)}\n`); };
  try { await fn(w, shot); } catch (e) { rec.error = String(e.stack || e); console.log('ERR', name, e.message); try { await shot(name + '-err'); } catch {} }
  rec.errors = w.errors(); rec.net = (await w.net()).map((n) => { let c = null; try { c = JSON.parse(n.resBody).code; } catch {} return { m: n.method, u: String(n.url).slice(0, 200), s: n.status, c, b: (c && c !== 200) || n.status >= 400 ? String(n.resBody).slice(0, 300) : '' }; });
  rec.bad = rec.net.filter((n) => n.b);
  rec.console = w.console().filter((l) => /error/i.test(l)).slice(0, 40);
  fs.writeFileSync(`${LOG}/${name}.json`, JSON.stringify(rec, null, 1));
  console.log('badNet', JSON.stringify(rec.bad.slice(0,10)));
  console.log('pageErrors', rec.errors.length, rec.errors.slice(0, 3)); console.log('consoleErr', rec.console.slice(0, 8).join('\n'));
  await w.close(); return rec;
}
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
