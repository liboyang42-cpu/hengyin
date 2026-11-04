import { run, S } from './lib.mjs';
// I1: lead opens 管理 › 结算 and the balance/earnings view
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500);
  await w.tap('结算', { exact: true }); await S(3500); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('settle'); await w.shot('138-club-settlement');
  await w.scroll(700); await w.say('settle2'); await w.shot('139-club-settlement-2');
});
