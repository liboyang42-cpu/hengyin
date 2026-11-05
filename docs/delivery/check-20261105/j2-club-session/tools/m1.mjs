import { run, S } from './lib.mjs';
// M1: 自由探索: seeded club lead 9003 opens club 7001 › 管理, the 自由探索 theme 990013 and its next step
await run({ path: 'pages/club/detail/index?id=7001', identity: 'e2e-openid-9003', agree: true }, async (w) => {
  await S(3000); await w.say('club'); await w.shot('163-fe-club-7001');
  try { await w.tap('管理', { exact: true }); await S(2500); } catch (e) { console.log('no 管理', e.message); }
  await w.say('manage'); await w.shot('164-fe-manage');
  await w.scroll(600); await S(400); await w.say('manage2'); await w.shot('165-fe-manage-2');
  try { await w.tap('自由探索期次'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('editions'); await w.shot('166-fe-editions'); } catch (e) { console.log('editions', e.message); }
});
