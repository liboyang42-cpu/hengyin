import { run, S } from './lib.mjs';
// H2: lead opens 活动导演台 from 管理 (before the session)
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.scroll(500); await S(400);
  await w.tap('活动导演台'); await S(4000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('director'); await w.shot('87-director-pre');
  await w.scroll(700); await S(400); await w.say('director2'); await w.shot('88-director-pre-2');
  await w.scroll(700); await S(400); await w.say('director3'); await w.shot('89-director-pre-3');
});
