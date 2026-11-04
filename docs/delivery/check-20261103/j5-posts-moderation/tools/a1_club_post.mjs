import { start, sleep } from './lib.mjs';
const N = process.env.N || 'x';
const texts = JSON.parse(process.env.TEXTS);
const w = await start({ path: 'pages/club/detail/index?id=7001', identity: process.env.WHO || 'e2e-openid-9003' });
await w.step('tab 帖子', () => w.tap('帖子', { exact: true }));
await sleep(1000);
let i = 0;
for (const t of texts) {
  i++;
  await w.step('open composer', () => w.tap('有什么新鲜好玩的分享吗？'));
  await sleep(800);
  await w.step('type', () => w.typeInto('.cmp-input', t));
  await w.snap(`${N}-${i}a-typed`);
  await w.step('发布', () => w.tapSel('.cmp-send'));
  await sleep(350);
  console.log('toasts', JSON.stringify(await w.toasts())); console.log('early', (await w.text()).slice(0,400));
  await w.snap(`${N}-${i}b-after`);
  w.lastBody(/post\/create|posts/);
  await sleep(1500);
  await w.dump();
  // close composer if still open
  try { await w.tap('取消', { exact: true }); await sleep(600); try { await w.modal('confirm'); } catch (e) {} } catch (e) {}
  await sleep(600);
}
await w.snap(`${N}-z-list`);
await w.finish();
