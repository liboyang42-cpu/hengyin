// Launch the app directly on a page (as a share card / scene entry would), shoot at 0.7 s and 3.5 s, print route,
// text, page errors and bad responses. Usage: node launchpeek.mjs <identity|guest> <path?query> <shotName> [agree:0|1]
import { sleep } from './lib.mjs';
import fs from 'node:fs'; import path from 'node:path';
const RIG = process.env.RIG_DIR || (process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig');
const { launch } = await import(RIG + '/mpweb/walk.mjs');
const [identity, url, name, agree] = process.argv.slice(2);
const SHOTS = path.resolve(new URL('..', import.meta.url).pathname, 'shots');
const w = await launch({ path: url, identity: identity === 'guest' ? undefined : identity, agree: agree !== '0', settle: 300 });
await w.page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
const shot = async (n) => { let q = 70, buf; for (;;) { buf = await w.page.screenshot({ type: 'jpeg', quality: q, clip: { x: 0, y: 0, width: 390, height: 844 } }); if (buf.length < 204800 || q <= 30) break; q -= 8; } fs.writeFileSync(path.join(SHOTS, n + '.jpg'), buf); };
await sleep(400); console.log('t0.7', (await w.route()).join('>')); await shot(name + '-0');
await sleep(3000); console.log('t3.7', (await w.route()).join('>')); await shot(name);
console.log('TEXT', (await w.text()).slice(0, 800));
console.log('ERR', JSON.stringify(w.errors().map((e) => e.split('\n').slice(0, 3).join(' / '))));
console.log('CONSOLE', JSON.stringify(w.console().filter((c) => /error/i.test(c) && !/home-banners|favicon/.test(c)).slice(0, 6)));
const net = await w.net(); console.log('NET', net.filter((n) => n.status >= 400 || (n.body && n.body.code && n.body.code != 200)).map((n) => n.status + ' ' + n.url.replace('/__api', '').slice(0, 90) + ' ' + ((n.body && n.body.msg) || '')).join(' ; '));
await w.close();
