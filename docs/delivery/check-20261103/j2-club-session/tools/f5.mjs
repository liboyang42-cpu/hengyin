import { run, S } from './lib.mjs';
// F5: member m2 opens the order, scrolls to 和队友一起出发, creates a team
await run({ path: 'subpackageMember/orderinfo/orderinfo?id=74', identity: 'j2-m2', agree: true }, async (w) => {
  await S(3500); await w.shot('79-member-order-paid');
  await w.scroll(600); await w.say('team'); await w.shot('80-member-order-team');
});
await run({ path: 'subpackageMember/orderinfo/orderinfo?id=74', identity: 'j2-m2', agree: true }, async (w) => {
  await S(3500); await w.scroll(600); await w.tap('叫上队友'); await S(2500); console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.shot('81-member-team-size'); await w.tap('2 人队伍'); await S(500); await w.tap('确定', { exact: true }); await S(3000); console.log('ROUTE3', JSON.stringify(await w.route())); await w.say('teamcreate'); await w.shot('82-member-team-created'); console.log('TEAM', JSON.stringify(await w.eval(`JSON.stringify(Object.keys(getCurrentPages().slice(-1)[0].data))`)).slice(0,400));
});
