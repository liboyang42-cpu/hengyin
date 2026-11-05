import { run, S } from './lib.mjs';
// K2: refund after settlement, organiser path: club 设置 › 报名名册 › 王一舟 → 清退并退款 → confirm.
// (The director's 报名参与者 rows don't open anything; tapping 王一舟 there does nothing.)
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tapSel('.cover-nav-act'); await S(1500); await w.say('settings'); await w.shot('144-club-settings');
  await w.tap('报名名册'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('enroll'); await w.shot('145-enroll-list');
  await w.tap('人民广场 · 喷泉报时'); await S(2500); await w.say('group'); await w.shot('145b-enroll-group');
  await w.tap('待核销', { exact: true, nth: 3 }); await S(3000); console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('detail'); await w.shot('146-participant-detail');
  const t = await w.text(); if (!t.includes('清退并退款')) { await w.scroll(600); await S(400); await w.say('detail2'); await w.shot('146b-participant-detail-2'); }
  await w.tap('清退并退款'); await S(1500); await w.say('confirm'); await w.shot('147-refund-confirm');
  await w.tap('退款并清退', { exact: true });
  await S(4000); await w.say('done'); await w.shot('148-refund-done');
});
