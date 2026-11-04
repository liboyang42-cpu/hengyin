import { run, S } from './lib.mjs';
// D5: merchant 合作中心 → 人民广场 · 喷泉报时 → 申请承接
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.say('coop'); await w.shot('50b-merchant-coop-center-after-window');
  await w.tap('人民广场 · 喷泉报时'); await S(3000);
  console.log('OPTS', JSON.stringify(await w.eval(`getCurrentPages().slice(-1)[0].options`)));
  await w.tap('申请承接'); await S(400); await w.say('toast'); await w.shot('53-merchant-apply-open'); await S(2000);
  await w.tap('报名', { exact: true }); await S(2500); await w.say('apply'); await w.shot('54-merchant-apply-submitted'); console.log('ROUTE2', JSON.stringify(await w.route()));
});
