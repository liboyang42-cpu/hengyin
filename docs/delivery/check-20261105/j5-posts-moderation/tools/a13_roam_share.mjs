// Player walks a short roam (GPS seam moves), finishes, opens 分享 → 发到广场 and types the post text.
import { start, sleep } from './lib.mjs';
const E = process.env, N = E.N || 'x';
const w = await start({ path: 'pages/roam/index', identity: E.WHO || 'e2e-openid-9004' });
await sleep(4000);
await w.step('开始漫游', () => w.tap('开始漫游', { nth: 1 })); await sleep(2000);
await w.step('同意并开启', () => w.tap('同意并开启', { exact: true })); await sleep(3000);
for (let i = 0; i < 8; i++) { await w.loc(31.2304 + i * 0.0004, 121.4737 + i * 0.0004); await sleep(1500); }
console.log('walking', (await w.text()).slice(0, 300));
await w.step('开启定位', () => w.tap('开启定位 · 开始点亮')); await sleep(2500); for (let i = 0; i < 6; i++) { await w.loc(31.2334 + i * 0.0004, 121.4767); await sleep(1500); } console.log('after loc', (await w.text()).slice(0, 300)); await w.step('暂停', () => w.tap('暂停', { exact: true })); await sleep(1500);
console.log('more', (await w.text()).slice(0, 500));
await w.snap(`${N}-0-more`);
await w.finish();
