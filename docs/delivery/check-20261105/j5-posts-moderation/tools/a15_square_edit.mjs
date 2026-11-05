// Author 9004 edits own 广场 post: ··· → 编辑 → compose (edit mode) → 保存.
import { start, sleep } from './lib.mjs';
const E = process.env, N = E.N || 'x';
const w = await start({ path: `pages/square/detail/index?id=${E.PID}`, identity: 'e2e-openid-9004' });
await sleep(4000);
await w.step('···', () => w.tapAt('.ph-more'));
await sleep(1200); console.log('menu', (await w.text()).slice(0, 300));
await w.step('编辑', () => w.tap('编辑', { exact: true })); await sleep(1800);
await w.step('正文', () => w.typeInto('textarea[placeholder="有什么新鲜好玩的分享吗？"]', E.TEXT));
await w.snap(`${N}-a-typed`);
await w.step('保存', () => w.tapAt('.cmp-send')); await sleep(2000); await w.snap(`${N}-a2-guideline`); await w.step('同意发布', () => w.modal('confirm')); await sleep(4000);
w.lastBody(/posts\/\d+$|PATCH|posts\/\d+\/publish/);
console.log('after', (await w.text()).slice(0, 400));
await w.snap(`${N}-b-after`);
for (const b of w.bodies.filter((b) => /community/.test(b.u) && !/comments|guidelines\/active/.test(b.u)).slice(-4)) console.log('  body', b.s, b.u, b.b.slice(0, 160));
await w.finish();
