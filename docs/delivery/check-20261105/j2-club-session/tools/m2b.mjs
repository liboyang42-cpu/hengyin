import { run, S } from './lib.mjs';
// M2: merchant 9002: 合作中心 › 人民广场 · 慢逛一下午 › 申请承接 (chapter) → fill → submit
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9005', agree: true }, async (w) => {
  await S(3500); await w.say('coop'); await w.shot('_x');
  await w.tap('人民广场 · 慢逛一下午'); await S(5000); await w.scroll(700); await S(600); await w.say('theme'); await w.shot('_x');
  await w.tap('申请承接'); await S(2000); await w.say('sheet'); await w.shot('_x');
  await w.tap('第2章 书店'); await S(600); { const tt = await w.text(); console.log('TPL', tt.split('选择玩法模板')[1]?.slice(0,200)); try { await w.tap('到店打卡'); } catch (e) {} } await S(600);
  await w.tap('下一步 · 填报名'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('form'); await w.shot('_x');
  await w.ty('玩家在路线上看到的名字', '福州路小书店'); 
  await w.eval(`window.MPWEB.locQueue = [{name:'福州路小书店', address:'上海市黄浦区福州路465号', lat:31.2350, lng:121.4790}]`);
  await w.tap('地图选点'); await S(1200); await w.shot('_x'); await w.tap('去地图选点'); await S(2000); await w.say('form2'); await w.shot('_x');
  await w.tap('提交申请与节点'); await S(3500); await w.say('submitted'); await w.shot('_x');
});
