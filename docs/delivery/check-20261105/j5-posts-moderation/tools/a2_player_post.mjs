import { start, sleep } from './lib.mjs';
import fs from 'node:fs';
const IM = JSON.parse(fs.readFileSync('/tmp/claude-0/j5-images.json', 'utf8'));
const N = process.env.N || 'x';
const w = await start({ path: 'pages/club/detail/index?id=7001', identity: 'e2e-openid-9004' });
if (process.env.JOIN) { await w.step('加入', () => w.tap('加入', { exact: true })); await sleep(1500); await w.snap(`${N}-0-joined`); console.log('early', (await w.text()).slice(0, 300)); w.lastBody(/join/); }
try { await w.tap('知道了', { exact: true }); await sleep(600); } catch (e) {}
await w.step('tab 帖子', () => w.tap('帖子', { exact: true })); await sleep(1000);
for (const [i, img, text] of JSON.parse(process.env.CASES)) {
  await w.step('open composer', () => w.tap('有什么新鲜好玩的分享吗？')); await sleep(800);
  await w.step('type', () => w.typeInto('.cmp-input', text));
  if (img) {
    await w.photo(IM[img]);
    await w.step('添加图片', () => w.tapSel('.cmp-tool')); await sleep(600);
    try { await w.sheet('从手机相册选择'); } catch (e) {}
    await sleep(1500);
    if ((await w.text()).includes('选取')) { await w.snap(`${N}-${i}a0-crop`); await w.step('选取', () => w.tap('选取', { exact: true })); }
    await sleep(2500);
    console.log('early-img', (await w.text()).slice(0, 200)); w.lastBody(/upload/);
    await w.snap(`${N}-${i}a-image`);
  }
  await w.step('发布', () => w.tapSel('.cmp-send')); await sleep(350);
  console.log('early', (await w.text()).slice(0, 200));
  await w.snap(`${N}-${i}b-after`); w.lastBody(/post\/create/); await sleep(1500);
  try { await w.tap('取消', { exact: true }); await sleep(800); } catch (e) {}
}
await w.snap(`${N}-z-list`);
await w.finish();
