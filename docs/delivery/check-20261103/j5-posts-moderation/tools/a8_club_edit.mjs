import { start, sleep } from './lib.mjs';
const N = process.env.N || 'x';
const w = await start({ path: 'pages/club/detail/index?id=7001', identity: 'e2e-openid-9003' });
await w.page.mouse.click(261, 68); await sleep(1200);
await w.step('编辑资料', () => w.tapAt('[aria-label="编辑资料"]')); await sleep(2500); await w.snap(`${N}-0-page`);
let i = 1;
for (const [city, intro] of JSON.parse(process.env.CASES)) {
  await w.step('城市', () => w.typeInto('input[placeholder="所在城市"]', city));
  await w.step('介绍', () => w.typeInto('textarea[placeholder="简单介绍你的俱乐部，以及你希望创造怎样的活动体验。"]', intro));
  await w.snap(`${N}-${i}a-typed`);
  await w.step('保存修改', () => w.tap('保存修改', { exact: true })); await sleep(350);
  console.log('early', (await w.text()).slice(0, 200)); await w.snap(`${N}-${i}b-saved`); w.lastBody(/club\/update/); await sleep(1500);
  i++;
}
await w.finish();
