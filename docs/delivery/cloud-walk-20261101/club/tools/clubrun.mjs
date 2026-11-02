// club walker helper (not committed): node clubrun.mjs opts.json script.mjs
import { launch } from './walk.mjs';
import path from 'node:path';
const opts = JSON.parse(process.argv[2]);
const mod = await import(path.resolve(process.argv[3]));
const w = await launch(opts);

try { await mod.default(w); } catch (e) { console.log('SCRIPT ERROR', e.message); }
console.log('route', JSON.stringify(await w.route()));
const net = await w.net(); console.log('--- net'); for (const n of net.slice(-40)) console.log(JSON.stringify(n).slice(0, 300));
console.log('--- errors', JSON.stringify(w.errors()).slice(0, 1500));
console.log('--- console', w.console().filter(x => /error|warn/.test(x) && !/favicon/.test(x)).slice(-15).join('\n'));
await w.browser.close();
