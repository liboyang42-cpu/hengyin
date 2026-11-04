import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('查看详情与报名'); await S(1500); await w.scroll(1700); await S(800);
  await w.shot('41-public-topic-dock-locked');
  await w.tapSel('.dock-cta'); await S(2500); console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('after'); await w.shot('42-after-dock-tap');
});
