import { run, S } from './lib.mjs';
// M2: merchant 9002: 合作中心 › 人民广场 · 慢逛一下午 › 申请承接 (chapter) → fill → submit
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.say('coop'); await w.shot('167-fe-merchant-coop-center');
  await w.tap('人民广场 · 慢逛一下午'); await S(5000); await w.scroll(700); await S(600); await w.say('theme'); await w.shot('168-fe-merchant-theme');
  await w.tap('申请承接'); await S(2000); await w.say('sheet'); await w.shot('169-fe-merchant-apply-sheet');
  await w.tap('第1章 咖啡'); await S(600); await w.tap('到店打卡·拍一张你的咖啡'); await S(600);
  await w.tap('下一步 · 填报名'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('form'); await w.shot('170-fe-merchant-apply-form');
  await w.ty('玩家在路线上看到的名字', '探店日样板咖啡'); 
  await w.eval(`window.MPWEB.locQueue = [{name:'探店日样板咖啡', address:'上海市黄浦区人民大道120号', lat:31.2312, lng:121.4745}]`);
  await w.tap('地图选点'); await S(1200); await w.shot('171a-fe-location-explainer'); await w.tap('去地图选点'); await S(2000); await w.say('form2'); await w.shot('171-fe-merchant-apply-filled');
  await w.tap('提交申请与节点'); await S(3500); await w.say('submitted'); await w.shot('172-fe-merchant-apply-submitted');
});
