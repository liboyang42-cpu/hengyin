import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990015', identity: 'e2e-openid-9004', agree: true }, async (w) => {
  await S(3000);
  await w.tap('查看详情与报名'); await S(1500);
  await w.tap('随时开玩'); await S(1500);
  await w.tap('去结算'); await S(2500); await w.shot('06-order-confirm'); await w.say('order');
  console.log('ROUTE1', JSON.stringify(await w.route()));
});
