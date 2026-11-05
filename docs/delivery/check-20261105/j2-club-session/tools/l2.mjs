import { run, S } from './lib.mjs';
// L2: new 主理人 (林一帆) opens the club, 管理 › 结算; former 主理人 opens 我的 › 我的资产 to find the ¥113.49
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-m1', agree: true }, async (w) => {
  await S(3000); try { await w.tap('知道了'); } catch (e) {} await w.say('new'); await w.shot('156-new-owner-club');
  await w.tap('管理', { exact: true }); await S(2000); await w.say('new-manage'); await w.shot('157-new-owner-manage');
  await w.tap('结算', { exact: true }); await S(3500); await w.say('new-settle'); await w.shot('158-new-owner-settlement');
});
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3000); await w.say('old'); await w.shot('159-old-owner-club');
});
await run({ path: 'pages/member/index/index', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.say('my'); await w.shot('160-old-owner-my');
  const t = await w.text(); const m = t.match(/我的资产|资产|钱包|收益/); console.log('ASSET', m && m[0]);
  if (m) { await w.tap(m[0]); await S(3500); console.log('ROUTE', JSON.stringify(await w.route())); await w.say('assets'); await w.shot('161-old-owner-assets');
    const t2 = await w.text(); const m2 = t2.match(/收益明细|明细/); if (m2) { await w.tap(m2[0]); await S(3500); await w.say('detail'); await w.shot('162-old-owner-income-detail'); } }
});
