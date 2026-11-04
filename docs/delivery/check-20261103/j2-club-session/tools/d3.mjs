import { run, S } from './lib.mjs';
// D3: lead opens 招商 page (same page the 商家 button opens, without auto-opening the layer) → 更多 → chapter 商家承接
await run({ path: 'pages/topic/merchantinfo/merchantinfo?topicId=990014&scope=CLUB', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.say('page'); await w.shot('47-merchantinfo-page');
  await w.tap('更多', { exact: true }); await S(1500); await w.say('more'); await w.shot('48-merchantinfo-more');
});
await run({ path: 'pages/topic/merchantinfo/merchantinfo?topicId=990014&scope=CLUB', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.tap('更多', { exact: true }); await S(1500);
  await w.tap('开放商家承接申请'); await S(2500); await w.say('opened'); await w.shot('49-chapter-recruit-opened');
});
