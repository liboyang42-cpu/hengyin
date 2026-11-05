import { start, sleep } from './lib.mjs';
const N = process.env.N || 'x';
const w = await start({ path: 'pages/merchant/decor/index', identity: 'e2e-openid-9002' });
await w.snap(`${N}-0-brand`);
await w.step('品牌介绍', () => w.tap('品牌介绍', { exact: true })); await sleep(1500);
let i = 1;
for (const t of JSON.parse(process.env.TEXTS)) {
  await w.step('编辑故事', () => w.tap('编辑', { exact: true, nth: 2 })); await sleep(1000);
  await w.step('输入', () => w.typeInto('textarea[placeholder="写下店铺的故事"]', t));
  await w.snap(`${N}-${i}a-typed`);
  await w.step('保存', () => w.tap('保存', { exact: true })); await sleep(350);
  console.log('early', (await w.text()).slice(0, 220)); await w.snap(`${N}-${i}b-saved`); w.lastBody(/decor\/save|merchant\/update/); await sleep(1800);
  try { await w.tap('取消', { exact: true }); } catch (e) {}
  await w.page.keyboard.press('Escape'); await sleep(500);
  i++;
}
await w.finish();
