import { start, sleep } from './lib.mjs';
const N = process.env.N || 'x';
const w = await start({ path: 'pages/gerenziliao/gerenziliao', identity: 'e2e-openid-9004' });
await w.snap(`${N}-0-page`);
let i = 1;
for (const [nick, intro] of JSON.parse(process.env.CASES)) {
  if (nick != null) {
    await w.step('昵称行', () => w.tap('昵称', { exact: true })); await sleep(800);
    await w.step('输入昵称', () => w.typeInto('input[placeholder="请输入昵称"]', nick));
  }
  if (intro != null) {
    await w.step('介绍行', () => w.tap('个人介绍', { exact: true })); await sleep(800);
    await w.step('输入介绍', () => w.typeInto('textarea[placeholder="介绍一下你自己"]', intro));
  }
  await w.snap(`${N}-${i}a-typed`);
  for (const b of ['完成', '确定']) { try { await w.tap(b, { exact: true }); await sleep(500); break; } catch (e) {} }
  await w.step('保存', () => w.tap('保存', { exact: true })); await sleep(350);
  console.log('early', (await w.text()).slice(0, 200)); await w.snap(`${N}-${i}b-saved`); w.lastBody(/user\/update/); await sleep(1500);
  i++;
}
await w.finish();
