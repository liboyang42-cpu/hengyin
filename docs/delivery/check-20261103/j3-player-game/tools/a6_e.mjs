import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990019', identity: 'e2e-openid-9004', agree: true }, async (w) => {
  await S(3000);
  await w.tap('查看详情与报名'); await S(1500); await w.tap('随时开玩'); await S(1500); await w.tap('去结算'); await S(2500);
  await w.shot('160-end-checkout'); await w.say('fresh');
  await w.tap('立即支付'); await S(1500); await w.shot('161-end-noconsent'); await w.say('noconsent');
  await w.tap('同意将姓名'); await S(800); await w.shot('162-end-consent');
  await w.pay('success');
  await w.tap('立即支付'); await S(6000); await w.shot('163-end-paid'); await w.say('paid');
  console.log('ROUTE1', JSON.stringify(await w.route()));
});
