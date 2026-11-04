import { run, S } from './lib.mjs';
// G1: lead opens 活动导演台 from 管理
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500); await w.scroll(500);
  await w.tap('活动导演台'); await S(3500); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('director'); await w.shot('85-director-pre');
  await w.scroll(700); await w.say('director2'); await w.shot('86-director-pre-2');
  await w.scroll(700); await w.say('director3'); await w.shot('87-director-pre-3');
});
