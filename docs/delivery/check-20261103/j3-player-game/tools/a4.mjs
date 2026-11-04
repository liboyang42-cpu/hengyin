import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990015', identity: 'e2e-openid-9004', agree: true }, async (w) => {
  await S(3000);
  await w.tap('查看详情与报名'); await S(1500); await w.tap('随时开玩'); await S(1500); await w.tap('去结算'); await S(2500);
  await w.tap('补全姓名和手机号'); await S(2500); await w.shot('07-profile-complete'); await w.say('profile');
  console.log('ROUTE1', JSON.stringify(await w.route()));
});
