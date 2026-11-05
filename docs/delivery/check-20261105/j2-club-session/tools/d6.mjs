import { run, S } from './lib.mjs';
// D6: 我的合作 in 可对接商家, and the theme console's 商家 tab (page opened without the layer)
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.scroll(800); await S(500);
  await w.tap('可对接商家'); await S(3000); await w.shot('46-lead-club-merchants');
  await w.tap('我的合作'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('mycoop'); await w.shot('46b-lead-my-coop');
});
await run({ path: 'pages/topic/merchantinfo/merchantinfo?topicId=990014&scope=CLUB', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(4000); await w.say('console'); await w.shot('47-console');
  await w.tap('商家', { exact: true }); await S(3000); console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('mtab'); await w.shot('47b-console-merchant-tab');
});
