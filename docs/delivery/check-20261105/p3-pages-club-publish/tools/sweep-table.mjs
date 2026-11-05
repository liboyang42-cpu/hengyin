// Summarise logs/r2-*.json into a markdown table (states seen, controls, page errors, taps with no visible effect).
import fs from 'node:fs';
import path from 'node:path';
const LOG = path.resolve(new URL('..', import.meta.url).pathname, 'logs');
const rows = [];
for (const f of fs.readdirSync(LOG).filter((f) => /^r2-.*\.json$/.test(f)).sort()) {
  const j = JSON.parse(fs.readFileSync(path.join(LOG, f)));
  const c = j.controls || [];
  const skipped = c.filter((x) => /SKIPPED/.test(x.result || '')).length;
  const tapped = c.filter((x) => x.result && !/SKIPPED|back \(not|not reachable|off-screen/.test(x.result));
  const errs = tapped.filter((x) => x.pageError).length;
  const noeff = tapped.filter((x) => /NO VISIBLE/.test(x.result));
  const text = (j.text || '').replace(/ \| 9:41.*$/, '').replace(/\|/g, '/').slice(0, 70);
  rows.push(`| ${j.id.replace(/^r2-/, '')} | \`${j.path}\` | ${j.identity === 'e2e-openid-9009' ? '9009' : j.identity.replace('e2e-openid-', '')} | ${(j.route || []).slice(-1)[0] === j.path.split('?')[0] ? 'opened' : 'went to ' + (j.route || []).slice(-1)[0]} | ${text} | ${j.controlCount} / ${tapped.length} tapped / ${skipped} skipped | ${errs} | ${noeff.length ? noeff.map((x) => (x.txt || x.h).slice(0, 12)).join(', ') : '—'} | ${j.id}-a-open |`);
}
console.log('| Page | Path | As | Route | First screen text | Controls | Page errors | Taps with no visible effect | Shot |');
console.log('|---|---|---|---|---|---|---|---|---|');
console.log(rows.join('\n'));
