import { run, S } from './lib.mjs';
// D7: recover the unsent invitation: 可对接商家 › the merchant's card › 邀约 → pick theme → 据点 → 引流合作 → 发送
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.scroll(800); await S(500);
  await w.tap('可对接商家'); await S(3000); await w.tap('探店日样板咖啡'); await S(3000);
  console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('mcard'); await w.shot('48-merchant-card-from-club');
  const t = await w.text(); const m = t.match(/邀请合作|发起合作|邀约|发邀约|邀请/); console.log('BTN', m && m[0]);
  if (m) { await w.tap(m[0]); await S(3500); }
  console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('invite'); await w.shot('49-lead-invite-from-card');
  await w.tap('人民广场 · 喷泉报时'); await S(2000); await w.say('picked'); await w.shot('50-lead-invite-theme-picked');
  const t3 = await w.text(); if (/请选择一个据点|选择据点|据点/.test(t3)) { try { await w.tap('请选择一个据点'); await S(1500); await w.say('st'); await w.shot('50b-station'); await w.tap('人民广场音乐喷泉', { nth: -1 }); await S(1000);} catch (e) { console.log('st', e.message); } }
  await w.tap('发送邀约', { nth: -1 }); await S(3500); console.log('ROUTE3', JSON.stringify(await w.route())); await w.say('sent'); await w.shot('51-lead-invite-sent');
});
