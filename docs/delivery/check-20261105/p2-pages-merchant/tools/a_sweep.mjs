import { go, sleep } from './go.mjs';
const pages = process.argv[2] ? JSON.parse(process.argv[2]) : [];
const id = process.argv[3] || 'e2e-openid-9002';
const prefix = process.argv[4] || 'a';
let i = 0;
for (const p of pages) {
  i++;
  const n = `${prefix}${String(i).padStart(2, '0')}-${p.split('?')[0].replace(/^pages\//, '').replace(/\/index$/, '').replace(/\//g, '-')}`;
  await go(n, { path: p.split('?')[0] + (p.includes('?') ? '' : ''), identity: id }, async (w, shot) => {
    if (p.includes('?')) await w.relaunch('/' + p);
    await sleep(1800); await shot(n);
  });
}
