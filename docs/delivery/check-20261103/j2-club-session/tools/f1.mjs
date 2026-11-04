import { run, S } from './lib.mjs';
// F1: member opens the club 活动 tab, opens the session
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-m2', agree: true }, async (w) => {
  await S(2500); try { await w.tap('去看活动'); } catch (e) { await w.tap('活动', { exact: true }); } await S(1500); await w.say('events'); await w.shot('71-member-club-events');
  await w.tap('人民广场 · 喷泉报时'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('topic'); await w.shot('72-member-topic');
});
