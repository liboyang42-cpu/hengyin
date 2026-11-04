import { run, S } from './lib.mjs';
// J3: old 主理人 and new 主理人 open the club
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.say('old'); await w.shot('147-old-owner-club');
  try { await w.tap('管理', { exact: true }); await S(1500); await w.say('old-manage'); await w.shot('148-old-owner-manage'); } catch (e) { console.log('no 管理 tab for old owner'); }
});
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-m1', agree: true }, async (w) => {
  await S(3000); try { await w.tap('知道了'); } catch (e) {} await w.say('new'); await w.shot('149-new-owner-club');
  await w.tap('管理', { exact: true }); await S(1500); await w.tap('结算', { exact: true }); await S(3000); await w.say('new-settle'); await w.shot('150-new-owner-settlement');
});
