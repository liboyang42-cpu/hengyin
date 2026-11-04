import { run, S } from './lib.mjs';
// C5: after approval, the lead opens the theme from 管理 and looks for the way to sale
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500);
  await w.say('manage'); await w.shot('34-manage-after-approval');
  await w.scroll(500); await w.tap('2026-11-04 · 0 人报名'); await S(3000);
  console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('topic'); await w.shot('35-club-topic-detail');
  await w.scroll(700); await w.say('topic2'); await w.shot('36-club-topic-detail-2');
  await w.scroll(700); await w.say('topic3'); await w.shot('37-club-topic-detail-3');
});
