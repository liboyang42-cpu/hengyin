import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990015', identity: 'e2e-openid-9004', agree: true }, async (w) => {
  await S(3000);
  await w.tap('查看详情与报名'); await S(1500); await w.shot('04-theme-detail');
  await w.tap('随时开玩'); await S(1500); await w.shot('05-checkout'); await w.say('checkout');
  console.log('DATA', JSON.stringify(await w.data('')).slice(0, 1500));
});
