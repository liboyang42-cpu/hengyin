import { run, S } from './lib.mjs';
// G0: does a real tap on the theme page reach its handlers? member j2b-m3, via the club session card.
await run({ path: 'pages/topic/index/index?id=990014&via=session', identity: 'j2b-m3', agree: true }, async (w) => {
  await S(3500);
  await w.tap('查看详情与报名'); await S(2000); await w.say('after-details'); await w.shot('79-topic-after-details-tap');
  for (let i = 0; i < 3; i++) { await w.scroll(700); await S(500); await w.say('scroll' + i); await w.shot('80-topic-scroll-' + i); }
  const t = await w.text(); const m = t.match(/立即报名|查看场次|去票夹|随时开玩/g); console.log('CTAS', JSON.stringify(m));
  await w.tap('立即报名'); await S(2500); await w.say('after-cta'); await w.shot('81-topic-after-cta-tap');
  console.log('ROUTE_A', JSON.stringify(await w.route()));
});
