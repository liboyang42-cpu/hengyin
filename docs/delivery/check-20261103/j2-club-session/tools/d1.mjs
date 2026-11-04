import { run, S } from './lib.mjs';
// D1: lead opens 商家 on the club theme page → 招商 layer
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('商家', { exact: true }); await S(3500);
  console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('recruit'); await w.shot('45-recruit-layer');
  await w.scroll(600); await w.say('recruit2'); await w.shot('46-recruit-layer-2');
});
