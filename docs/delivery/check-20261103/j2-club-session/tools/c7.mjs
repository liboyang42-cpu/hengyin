import { run, S } from './lib.mjs';
// C7: public theme page as the lead → scroll to the sale block → tap the sale button (lockedTip → pricing)
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('查看详情与报名'); await S(1500); await w.scroll(844); await S(800);
  await w.say('second'); await w.shot('40-public-topic-second-as-lead');
  await w.scroll(844); await w.say('third'); await w.shot('41-public-topic-third-as-lead');
});
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('查看详情与报名'); await S(1500); await w.scroll(1700); await S(800);
  await w.tap('定价中 · 未开售'); await S(2500);
  console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('pricing'); await w.shot('42-pricing-open');
});
