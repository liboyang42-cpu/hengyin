import { start, sleep } from './lib.mjs';
const N = process.env.N || 'x';
const w = await start({ path: 'pages/club/detail/index?id=7001', identity: 'e2e-openid-9004' });
try { await w.tap('知道了', { exact: true }); await sleep(500); } catch (e) {}
await w.step('tab 帖子', () => w.tap('帖子', { exact: true })); await sleep(1200);
await w.snap(`${N}-1-feed`);
const SKIP = process.env.SKIP;
const likeSel = '[aria-label^="点赞，当前"]';
if (!SKIP) await w.step('点赞', () => w.tapAt(likeSel, -1)); await sleep(1200); w.lastBody(/like/);
await w.snap(`${N}-2-liked`);
if (!SKIP) await w.step('关注', () => w.tapAt('[aria-label^="关注 "]', -1)); await sleep(1200); w.lastBody(/follow/);
console.log('early', (await w.text()).slice(0, 300)); await w.snap(`${N}-3-followed`);
await w.step('评论', () => w.tapAt('[aria-label^="查看评论，当前"]', -1)); await sleep(1200);
for (const [i, t] of [[4, '这条 zqvxlm 不错'], [5, '这条 z q v x l m 不错'], [6, '这条 zqvxlmpark 不错']]) {
  await w.step('输入评论', () => w.typeInto('input[placeholder="写评论…"]', t));
  await w.step('发送', () => w.tap('发送', { exact: true })); await sleep(350);
  console.log('early', (await w.text()).slice(0, 200)); await w.snap(`${N}-${i}-comment`); w.lastBody(/comment/); await sleep(1500);
}
await w.snap(`${N}-7-comments`);
await w.finish();
