import { run, S } from './lib.mjs';
// D5: after leaving the invite page without sending: what does the lead see, and how does he get back to the invitation?
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000);
  await w.tap('去招商', { nth: 1 }); await S(4000); await w.shot('42-recruit-layer-after-confirm');
  await w.tap('探店日样板咖啡'); await S(2000); console.log('ROUTE_ROW', JSON.stringify(await w.route())); await w.say('row'); await w.shot('43-recruit-confirmed-row-tap');
});
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.say('manage'); await w.shot('44-manage-after-confirm');
  await w.scroll(800); await S(500); await w.tap('我发出的邀约'); await S(3000); console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('sent'); await w.shot('45-lead-sent-invites');
});
