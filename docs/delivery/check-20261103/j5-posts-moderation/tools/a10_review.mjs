import { start, sleep } from './lib.mjs';
import fs from 'node:fs';
const IM = JSON.parse(fs.readFileSync('/tmp/claude-0/j5-images.json', 'utf8'));
const N = process.env.N || 'x';
const [text, img] = JSON.parse(process.env.CASE);
const w = await start({ path: 'pages/topic/index/index?id=990014', identity: 'e2e-openid-9009' });
try { await w.tap('查看详情与报名', { exact: true }); await sleep(1500); } catch (e) {}
await w.scroll(4000); await sleep(800);
await w.step('写评价', async () => { try { await w.tap('写评价', { exact: true }); } catch (e) { await w.tap('还没有评价'); } }); await sleep(1500); console.log('after-open', (await w.text()).slice(0, 300));
await w.step('5 星', () => w.tapAt('[aria-label="评 5 星"]').catch(() => {}));
await w.step('输入', () => w.typeInto('textarea[placeholder="请输入您的评价内容..."]', text));
if (img) {
  await w.photo(IM[img]);
  await w.step('加图', () => w.tapAt('.item.flex-col.flex-cc')); await sleep(1500);
  if ((await w.text()).includes('选取')) await w.step('选取', () => w.tap('选取', { exact: true }));
  await sleep(2500); w.lastBody(/upload/); console.log('early-img', (await w.text()).slice(0, 160));
}
await w.snap(`${N}-a-typed`);
const btns = (await w.text()).match(/提交|发布|发表/g); console.log('btns', btns);
await w.step('提交', () => w.tap(btns ? btns[btns.length - 1] : '提交', { exact: true })); await sleep(350);
console.log('early', (await w.text()).slice(0, 200)); await w.snap(`${N}-b-after`); w.lastBody(/comment\/add/); await sleep(1500);
await w.finish();
