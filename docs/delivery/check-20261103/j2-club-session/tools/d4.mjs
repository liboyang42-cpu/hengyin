import { run, S } from './lib.mjs';
// D4: merchant opens 合作中心 → the club theme → applies for a station
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.shot('50-merchant-coop-center');
  await w.tap('看详情', { nth: 0 }); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('detail'); await w.shot('51-merchant-theme-detail');
  await w.scroll(700); await w.say('detail2'); await w.shot('52-merchant-theme-detail-2');
});
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.tap('看详情', { nth: 0 }); await S(3000);
  console.log('OPTS', JSON.stringify(await w.eval(`getCurrentPages().slice(-1)[0].options`)));
  await w.tap('申请承接'); await S(300); await w.say('toast'); await w.shot('53a-merchant-apply-toast'); await S(2700); console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.say('apply'); await w.shot('53-merchant-apply-open');
  await w.scroll(700); await w.say('apply2'); await w.shot('54-merchant-apply-2');
});
